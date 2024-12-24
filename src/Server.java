import com.google.gson.Gson;

import java.io.*;
import java.net.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private static final int PORT = 12345;
    private static final Gson gson = new Gson();
    private static final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();
    private static final ConcurrentHashMap<String, String> connectRequestClients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> commandRequestClients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            log("Server", "Server started at port " + PORT + ", waiting for connections...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            logErr("Server", "Error starting server: " + e.getMessage());
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private BufferedReader in;
        private PrintWriter out;
        private String clientKey;


        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out = new PrintWriter(clientSocket.getOutputStream(), true);

                String ipAddress = clientSocket.getInetAddress().getHostAddress();
                clientKey = ipAddress + ":" + clientSocket.getPort();
                clients.put(clientKey, this);
                log("Server", "New client connected: ", clientKey);
                DatabaseManager.saveClient(clientSocket.getInetAddress().getHostAddress(), clientSocket.getPort());

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    log("Server", "Received from ", clientKey, inputLine);
                    handleMessage(inputLine);
                }

            } catch (IOException e) {
                logErr("Server", "Connection error with client ", clientKey, e.getMessage());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                disconnect();
            }
        }


        private void handleLogin(Message message) {
            try {
                if (DatabaseManager.isValidUser(message.getMessage(), message.getSecondMessage())) {
                    sendMessage(new Message("LOGIN_SUCCESS"));
                } else {
                    sendMessage(new Message("LOGIN_DENIED"));
                }
            } catch (Exception e) {
                logErr("Server", "Error during login: " + e.getMessage());
                sendMessage(new Message("ERROR"));
            }
        }

        private void handleRegister(Message message) {
            try {
                if (DatabaseManager.registerUser(message.getMessage(), message.getSecondMessage())) {
                    sendMessage(new Message("REGISTER_SUCCESS"));
                } else {
                    sendMessage(new Message("REGISTER_FAILED"));
                }
            } catch (Exception e) {
                logErr("Server", "Error during registration: " + e.getMessage());
                sendMessage(new Message("ERROR"));
            }
        }

        private void handleMessage(String input) {
            try {
                Message message = gson.fromJson(input, Message.class);

                switch (message.getType()) {
                    case "LOGIN":
                        handleLogin(message);
                        break;
                    case "REGISTER":
                        handleRegister(message);
                        break;
                    case "PING":
                        sendMessage(new Message("PONG"));
                        break;
                    case "CONNECT_REQUEST":
                        handleConnectRequest(message);
                        break;
                    case "CONNECT_ACCEPTED":
                        handleConnectAccepted(message);
                        break;
                    case "SEND_TO":
                        handleSendTo(message);
                        break;
                    case "EXECUTE":
                        handleExecute(message);
                        break;
                    case "COMMAND_RESULT":
                        handleCommandResult(message);
                        break;
                    default:
                        log("Server", "Unknown message type from client ", clientKey);
                }
            } catch (Exception e) {
                logErr("Server", "Error handling message from client ", clientKey, e.getMessage());
            }
        }
        private void handleConnectRequest(Message message) {
            String targetIP = message.getTargetIP();
            if (targetIP.startsWith("/")) {
                targetIP = targetIP.substring(1);
            }

            String targetKey = targetIP + ":" + message.getTargetPort();
            ClientHandler targetClient = clients.get(targetKey);
            if (targetClient != null) {
                connectRequestClients.put(targetKey, clientKey);
                targetClient.sendMessage(new Message("CONNECT_REQUEST",
                        clientSocket.getInetAddress().toString(),
                        clientSocket.getPort(),
                        message.getSenderLogin(),
                        null));
            } else {
                sendMessage(new Message("NO_SUCH_USER"));
            }
        }
        private void handleConnectAccepted(Message message) {
            String requestingClientKey = connectRequestClients.get(clientKey);
            if (requestingClientKey != null) {
                ClientHandler requestingClient = clients.get(requestingClientKey);
                if(requestingClient!=null){
                    requestingClient.sendMessage(new Message("CONNECT_ACCEPTED",
                            message.getTargetIP(),
                            message.getTargetPort()));
                    connectRequestClients.remove(clientKey);
                }
                else {
                    logErr("Server", "Request client not found: ", clientKey);
                    connectRequestClients.remove(clientKey);
                }
            } else {
                logErr("Server","No client for connection request: ", clientKey);
            }
        }

        private void handleSendTo(Message message) {
            String targetIP = message.getTargetIP();
            if (targetIP.startsWith("/")) {
                targetIP = targetIP.substring(1);
            }
            String targetKey = targetIP + ":" + message.getTargetPort();
            ClientHandler targetClient = clients.get(targetKey);
            if (targetClient != null) {
                commandRequestClients.put(targetKey,clientKey);
                targetClient.sendMessage(new Message("EXECUTE", clientSocket.getInetAddress().toString(),
                        clientSocket.getPort(), message.getSenderLogin(), message.getMessage()));
            } else {
                sendMessage(new Message("NO_SUCH_USER"));
            }
        }
        private void handleExecute(Message message) {
            ClientHandler targetClient = clients.get(message.getTargetIP()+":"+message.getTargetPort());
            String[] clientKeyParts = clientKey.split(":");
            if(targetClient!=null){
                message.setTargetIP(clientKeyParts[0]);
                message.setTargetPort(Integer.parseInt(clientKeyParts[1]));
                targetClient.sendMessage(message);
            }
            else{
                logErr("Server","No client for command request: ", clientKey);
                commandRequestClients.remove(clientKey);
            }

        }
        private void handleCommandResult(Message message) {
            String requestingClientKey = message.getTargetIP()+":"+message.getTargetPort();
            System.out.println(requestingClientKey);
            ClientHandler requestingClient = clients.get(requestingClientKey);
            if (requestingClient != null) {
                requestingClient.sendMessage(message);
                commandRequestClients.remove(clientKey);
            }

        }


        private void sendMessage(Message message) {
            String jsonMessage = gson.toJson(message);
            out.println(jsonMessage);
            log("Server", "Sent to ", clientKey, jsonMessage);
        }

        private void disconnect() {
            try {
                if (clientKey != null) {
                    clients.remove(clientKey);
                    log("Server", "Client disconnected: ", clientKey);
                }
                if (in != null) in.close();
                if (out != null) out.close();
                clientSocket.close();
            } catch (IOException e) {
                logErr("Server", "Error closing connection for client ", clientKey, e.getMessage());
            }
        }
    }
    private static void log(String tag, String message, String... args) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder logMessage = new StringBuilder();
        logMessage.append(String.format("%s [%s]: %s", timestamp, tag, message));

        if (args != null && args.length > 0) {
            logMessage.append(" - ");
            for (int i = 0; i < args.length; i++) {
                logMessage.append(args[i]);
                if (i < args.length - 1) {
                    logMessage.append(", ");
                }
            }
        }
        System.out.println(logMessage);
    }

    private static void logErr(String tag, String message, String... args) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        StringBuilder logMessage = new StringBuilder();
        logMessage.append(String.format("%s [%s]: %s", timestamp, tag, message));

        if (args != null && args.length > 0) {
            logMessage.append(" - ");
            for (int i = 0; i < args.length; i++) {
                logMessage.append(args[i]);
                if (i < args.length - 1) {
                    logMessage.append(", ");
                }
            }
        }
        System.err.println(logMessage);
    }
}