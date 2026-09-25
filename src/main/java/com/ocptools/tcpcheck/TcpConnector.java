package com.ocptools.tcpcheck;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@ApplicationScoped
public class TcpConnector {

    public void connect(InetSocketAddress destination, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(destination, timeoutMillis);
        }
    }
}
