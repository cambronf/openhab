/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smaenergymeter.internal.packet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import org.openhab.binding.smaenergymeter.internal.handler.EnergyMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PacketListener} class is responsible for communication with the SMA devices.
 * It handles udp/multicast traffic and broadcast received data to subsequent payload handlers.
 *
 * @author Łukasz Dywicki - Initial contribution
 */
public class PacketListener {
    private final Logger logger = LoggerFactory.getLogger(ReceivingTask.class);
    private final DefaultPacketListenerRegistry registry;
    private final List<PayloadHandler> handlers = new CopyOnWriteArrayList<>();

    private String multicastGroup;
    private int port;

    public static final String DEFAULT_MCAST_GRP = "239.12.255.254";
    public static final int DEFAULT_MCAST_PORT = 9522;

    private MulticastSocket socket;
    private ScheduledFuture<?> future;
    private InetAddress address;

    public PacketListener(DefaultPacketListenerRegistry registry, String multicastGroup, int port) {
        this.registry = registry;
        this.multicastGroup = multicastGroup;
        this.port = port;
    }

    public void addPayloadHandler(PayloadHandler handler) {
        handlers.add(handler);
    }

    public void removePayloadHandler(PayloadHandler handler) {
        handlers.remove(handler);

        if (handlers.isEmpty()) {
            registry.close(multicastGroup, port);
        }
    }

    public boolean isOpen() {
        return socket != null && !socket.isClosed();
    }

    public void open(int intervalSec) throws IOException {
        if (isOpen()) {
            logger.debug("no need to bind socket second time");
            return;
        }
        socket = new MulticastSocket(port);
        socket.setSoTimeout(5000);
        address = InetAddress.getByName(multicastGroup);
        logger.debug("JoinGroup: " + multicastGroup);
        socket.setReuseAddress(true);
        // socket.joinGroup(address);

        future = registry.addTask(new ReceivingTask(socket, address, multicastGroup + ":" + port, handlers),
                intervalSec);
    }

    void close() throws IOException {
        if (future != null) {
            future.cancel(true);
        }

        address = InetAddress.getByName(multicastGroup);
        socket.leaveGroup(address);
        logger.debug("LeaveGroup: " + multicastGroup);
        socket.close();
    }

    public void request() {
        registry.execute(new ReceivingTask(socket, address, multicastGroup + ":" + port, handlers));
    }

    static class ReceivingTask implements Runnable {
        private final Logger logger = LoggerFactory.getLogger(ReceivingTask.class);
        private final MulticastSocket socket;
        private final InetAddress address;
        private final String group;
        private final List<PayloadHandler> handlers;

        ReceivingTask(MulticastSocket socket, InetAddress address, String group, List<PayloadHandler> handlers) {
            this.socket = socket;
            this.address = address;
            this.group = group;
            this.handlers = handlers;
        }

        public void run() {
            try {
                // byte[] bytes = new byte[608];
                byte[] bytes1 = new byte[608];
                byte[] bytes2 = new byte[608];
                byte[] bytes3 = new byte[608];
                // DatagramPacket msgPacket = new DatagramPacket(bytes, bytes.length);
                DatagramPacket msgPacket1 = new DatagramPacket(bytes1, bytes1.length);
                DatagramPacket msgPacket2 = new DatagramPacket(bytes2, bytes2.length);
                DatagramPacket msgPacket3 = new DatagramPacket(bytes3, bytes3.length);

                socket.joinGroup(address);

                do {
                    socket.receive(msgPacket1);
                } while (msgPacket1.getLength() < 600);

                try {
                    EnergyMeter meter1 = new EnergyMeter();
                    // meter.parse(bytes);
                    logger.debug("[Multicast UDP message received] meter 1 >> " + msgPacket1.getOffset() + " "
                            + msgPacket1.getLength());
                    meter1.parse(msgPacket1.getData(), msgPacket1.getLength());

                    for (PayloadHandler handler : handlers) {
                        handler.handle(meter1);
                    }
                } catch (IOException e) {
                    logger.info("Unexpected payload received for group {}, meter 1", group, e);
                }

                do {
                    socket.receive(msgPacket2);
                } while (msgPacket2.getLength() < 600);

                try {
                    EnergyMeter meter2 = new EnergyMeter();
                    // meter.parse(bytes);
                    logger.debug("[Multicast UDP message received] meter 2 >> " + msgPacket2.getOffset() + " "
                            + msgPacket2.getLength());
                    meter2.parse(msgPacket2.getData(), msgPacket2.getLength());

                    for (PayloadHandler handler : handlers) {
                        handler.handle(meter2);
                    }
                } catch (IOException e) {
                    logger.info("Unexpected payload received for group {}, meter 2", group, e);
                }
                do {
                    socket.receive(msgPacket3);
                } while (msgPacket3.getLength() < 600);

                socket.leaveGroup(address);

                try {
                    EnergyMeter meter3 = new EnergyMeter();
                    // meter.parse(bytes);
                    logger.debug("[Multicast UDP message received] meter 3 >> " + msgPacket3.getOffset() + " "
                            + msgPacket3.getLength());
                    meter3.parse(msgPacket3.getData(), msgPacket3.getLength());

                    for (PayloadHandler handler : handlers) {
                        handler.handle(meter3);
                    }
                } catch (IOException e) {
                    logger.info("Unexpected payload received for group {}, meter 3", group, e);
                }
            } catch (IOException e) {
                logger.warn("Failed to receive data for multicast group {}", group, e);
            }
        }
    }
}
