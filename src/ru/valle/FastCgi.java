package ru.valle;


import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class FastCgi {
    public static final byte BEGIN_REQUEST = 1;
    public static final byte END_REQUEST = 3;
    public static final int PARAMETERS = 4;

    public static final byte REQUEST_COMPLETE = 0;
    public static final byte NO_MULTIPLEX_CONNECTION = 1;

    public static final byte STDIN = 5;
    public static final byte STDOUT = 6;

    private static final int FCGI_KEEP_CONN = 1;
    private static final int FCGI_RESPONDER = 1;
    private static final int DEFAULT_PORT = 9000;


    public static void main(String args[]) throws IOException {
        ServerSocket serverSocket;
        try {
            Properties environment = System.getProperties();
            int portNumber = Integer.parseInt(environment.getProperty("FCGI_PORT"));
            serverSocket = new ServerSocket(portNumber);
        } catch (Exception exception) {
            System.out.println("Missing \"FCGI_PORT\" in environment, using default " + DEFAULT_PORT);
            serverSocket = new ServerSocket(DEFAULT_PORT);
        }
        System.out.println("FastCGI server stared " + serverSocket);
        try {
            Socket socket = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            while (true) {
                if (socket == null) {
                    socket = serverSocket.accept();
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    System.out.println("accepted socket " + socket);
                }
                HashMap<String, String> properties = null;
                int requestId = 0;
                boolean closeConnection = true;
                byte[] data = null;

                FastCgiMessage message;
                do {
                    message = new FastCgiMessage(inputStream);
                    switch (message.type) {
                        case BEGIN_REQUEST:
                            if (requestId != 0) {
                                System.out.println("reject extra request with id " + message.requestId);
                                //server tries to send multiplexed connection, but we process it only one by one, reject request:
                                new FastCgiMessage(END_REQUEST, message.requestId, NO_MULTIPLEX_CONNECTION).write(outputStream);
                            } else {
                                requestId = message.requestId;
                                closeConnection = (message.content[2] & FCGI_KEEP_CONN) == 0;
                                int requestRole = ((message.content[0] & 0xff) << 8) | (message.content[1] & 0xff);
                                if (requestRole != FCGI_RESPONDER) {
                                    throw new IOException("Only responder role is supported");
                                }
                                System.out.println("accept request id " + requestId);
                            }
                            break;

                        case STDIN:
                            System.out.println("STDIN " + message.contentLength);
                            if (message.contentLength > 0) {
                                if (data == null) {
                                    data = message.content;
                                } else {
                                    byte[] concatenated = new byte[data.length + message.contentLength];
                                    System.arraycopy(data, 0, concatenated, 0, data.length);
                                    System.arraycopy(message.content, 0, concatenated, data.length, message.contentLength);
                                    data = concatenated;
                                }
                            }
                            break;

                        case PARAMETERS:
                            if (message.contentLength > 0) {
                                int[] length = new int[2];
                                int offset = 0;
                                properties = new HashMap<String, String>();
                                while (offset < message.contentLength) {
                                    for (int i = 0; i < 2; i++) {
                                        length[i] = message.content[offset++];
                                        if ((length[i] & 0x80) != 0) {
                                            length[i] = ((length[i] & 0x7f) << 24) |
                                                    ((message.content[offset++] & 0xff) << 16) |
                                                    ((message.content[offset++] & 0xff) << 8) |
                                                    (message.content[offset++] & 0xff);
                                        }
                                    }
                                    String name = new String(message.content, offset, length[0]);
                                    String value = new String(message.content, offset + length[0], length[1]);
                                    System.out.println("PARAM " + name + " = " + value);
                                    properties.put(name, value);
                                    offset += length[0] + length[1];
                                }
                            }
                            break;
                    }
                }
                while (message.type != STDIN || message.contentLength != 0);

                new FastCgiMessage(STDOUT, requestId, processRequest(data, properties).getBytes()).write(outputStream);
                new FastCgiMessage(STDOUT, requestId).write(outputStream);
                new FastCgiMessage(END_REQUEST, requestId, REQUEST_COMPLETE).write(outputStream);

                if (closeConnection) {
                    System.out.println("finished request id " + requestId);
                    try {
                        outputStream.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    socket = null;
                } else {
                    System.out.println("finished request id " + requestId);
                    outputStream.flush();
                }
            }
        } finally {
            serverSocket.close();
        }
    }

    private static String processRequest(byte[] data, HashMap<String, String> properties) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Content-Type: text/plain\r\n\r\n");

        if (properties == null) {
            sb.append("no parameters");
        } else {
            sb.append("Request method: ").append(properties.get("REQUEST_METHOD")).append("\n");
            sb.append("Path: ").append(properties.get("SCRIPT_NAME")).append("\n");
            sb.append("Query: ").append(properties.get("QUERY_STRING")).append("\n");
            if (data != null && data.length > 0) {
                sb.append("DATA: ").append(new String(data, "UTF-8")).append("\n");
            }
            sb.append('\n');
            for (Map.Entry<String, String> stringStringEntry : properties.entrySet()) {
                sb.append(stringStringEntry.getKey()).append(" = ").append(stringStringEntry.getValue());
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}


