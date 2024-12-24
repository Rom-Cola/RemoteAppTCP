import com.google.gson.Gson;

import java.io.*;
import java.net.*;

public class Client {
    private Socket clientSocket;
    private BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    private PrintWriter out;
    private BufferedReader in;
    private final String serverAddress = "localhost";
    private final int serverPort = 12345;
    private final Gson gson = new Gson();
    private String login;
    private  boolean isConnectedToAnotherClient = false;
    private String currentTargetIP;
    private int currentTargetPort;

    public void start() {
        while (true) {
            System.out.println("Спроба підключитись до серверу...");
            if (!connectToServer()) {
                continue;
            }
            System.out.println("Підключення успішне!");
            menu();
            break;
        }
    }

    public boolean connectToServer() {
        boolean isConnected = false;

        while (!isConnected) {
            System.out.println("Спроба підключитись до серверу...");
            try {
                clientSocket = new Socket(serverAddress, serverPort);
                out = new PrintWriter(clientSocket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                Message testMessage = new Message("PING");
                sendMessageToServer(testMessage);

                String response = receiveMessageFromServer(5000);
                if (response != null) {
                    Message responseMessage = gson.fromJson(response, Message.class);
                    if ("PONG".equals(responseMessage.getType())) {
                        System.out.println("Підключення успішне!");
                        isConnected = true;
                    } else {
                        System.out.println("відповідь сервера: " + responseMessage.getType());
                    }
                } else {
                    System.out.println("Сервер не відповів вчасно.");
                }

            } catch (IOException e) {
                System.err.println("Не вдалося підключитися до сервера: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        return isConnected;
    }


    private void menu() {
        String choice;
        boolean isAuth = false;
        while (!isAuth) {
            System.out.println("=== Консольне меню ===");
            System.out.println("1. Логін");
            System.out.println("2. Реєстрація");
            System.out.println("0. Вийти");

            try {
                choice = reader.readLine();
            } catch (IOException e) {
                throw new RuntimeException("Не вийшло прочитати вхідні дані від користувача", e);
            }

            switch (choice) {
                case "1":
                    if (login()) {
                        isAuth = true;
                    }
                    break;
                case "2":
                    if (register()) {
                        isAuth = true;
                    }
                    break;
                case "0":
                    System.out.println("Вихід з програми.");
                    return;
                default:
                    System.out.println("Невірний вибір, спробуйте ще раз.");
            }
        }

        boolean exitStatus = false;
        while (!exitStatus) {
            System.out.println("=== Консольне меню ===");
            System.out.println("1. Підключитись до іншого клієнта");
            System.out.println("2. Надати підключення до свого клієнта");
            System.out.println("0. Вийти");

            try {
                choice = reader.readLine();
            } catch (IOException e) {
                throw new RuntimeException("Не вийшло прочитати вхідні дані від користувача", e);
            }

            switch (choice) {
                case "1":
                    connectToAnotherClient();
                    break;
                case "2":
                    allowConnectionToYourClient();
                    break;
                case "0":
                    exitStatus = true;
                    System.out.println("Вихід.");
                    break;
                default:
                    System.out.println("Невірний вибір, спробуйте ще раз.");
            }
        }
    }

    private boolean login() {
        try {
            System.out.println("Введіть логін:");
            String login = reader.readLine();
            System.out.println("Введіть пароль:");
            String password = reader.readLine();

            Message credentials = new Message("LOGIN", login, password);
            sendMessageToServer(credentials);

            String response = receiveMessageFromServer(10000);
            Message responseMessage = gson.fromJson(response, Message.class);
            if ("LOGIN_SUCCESS".equals(responseMessage.getType())) {
                System.out.println("Авторизація успішна.");
                this.login = login;
                return true;
            } else {
                System.out.println("Авторизація не вдалася.");
                return false;
            }
        } catch (IOException e) {
            System.out.println("Помилка підключення: " + e.getMessage());
            return false;
        }
    }

    private boolean register() {
        try {
            System.out.println("Введіть новий логін:");
            String login = reader.readLine();
            System.out.println("Введіть новий пароль:");
            String password = reader.readLine();

            Message message = new Message("REGISTER", login, password);
            sendMessageToServer(message);

            String response = receiveMessageFromServer(10000);
            Message responseMessage = gson.fromJson(response, Message.class);
            if ("REGISTER_SUCCESS".equals(responseMessage.getType())) {
                System.out.println("Реєстрація успішна.");
                this.login = login;
                return true;
            } else {
                System.out.println("Реєстрація не вдалася.");
                return false;
            }
        } catch (IOException e) {
            System.out.println("Помилка підключення: " + e.getMessage());
            return false;
        }
    }

    private void connectToAnotherClient() {
        String targetIP;
        int targetPort;
        try {
            System.out.println("Введіть IP клієнта для підключення:");
            targetIP = reader.readLine();
            System.out.println("Введіть порт клієнта:");
            targetPort = Integer.parseInt(reader.readLine());
        } catch (IOException e) {
            System.err.println("Не вийшло прочитати вхідні дані від користувача");
            throw new RuntimeException(e);
        }
        currentTargetIP = targetIP;
        currentTargetPort=targetPort;
        Message message = new Message("CONNECT_REQUEST", targetIP, targetPort, login, null);
        sendMessageToServer(message);

        System.out.println("Чекаємо дозволу на підключення (30 секунд)");
        try {
            String serverResponse = receiveMessageFromServer(30000);
            Message receivedMessage = gson.fromJson(serverResponse, Message.class);

            if ("CONNECT_DENIED".equals(receivedMessage.getType())) {
                System.out.println("Підключення відхилено.");
                return;
            } else if ("CONNECT_ACCEPTED".equals(receivedMessage.getType())) {
                System.out.println("Підключення підтверджено. Тепер ви можете надсилати команди.");
                isConnectedToAnotherClient=true;
//                new Thread(() -> {
//                    while (!Thread.currentThread().isInterrupted()) {
//                        try {
//                            Thread.sleep(10000);
//                            sendMessageToServer(new Message("PING"));
//                        } catch (InterruptedException e) {
//                            Thread.currentThread().interrupt();
//                        }
//                    }
//                }).start();

            }  else if ("NO_SUCH_USER".equals(receivedMessage.getType())) {
                System.out.println("Такого клієнта немає в системі");
                return;
            }
        } catch (IOException e) {
            System.err.println("Не вдалося отримати відповідь на запит підключення.");
            return;
        }

        while (isConnectedToAnotherClient) {
            String command;
            try {
                System.out.println("Введіть команду для виконання (0 to exit):");
                command = reader.readLine();
            } catch (IOException e) {
                System.err.println("Не вийшло прочитати вхідні дані від користувача");
                throw new RuntimeException(e);
            }
            if ("0".equals(command)) {
                isConnectedToAnotherClient=false;
                break;
            }

            Message executeMessage = new Message("EXECUTE", currentTargetIP, currentTargetPort, null, command);
            sendMessageToServer(executeMessage);
            try {
                String serverResponse = receiveMessageFromServer(10000);
                Message receivedMessage = gson.fromJson(serverResponse, Message.class);
                if("COMMAND_RESULT".equals(receivedMessage.getType())){
                    System.out.println("Результат виконання команди: " + receivedMessage.getMessage());
                } else {
                    System.out.println("Відповідь від сервера: " + serverResponse);
                }
            } catch (IOException e) {
                System.err.println("Відповідь не прийшла, перевірте підключення та повторіть спробу");
            }
        }
    }

    private void allowConnectionToYourClient() {
        System.out.println("Очікування запитів на підключення...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String messageString = receiveMessageFromServer(10000);
                if (messageString == null) {
                    continue;
                }
                Message message = gson.fromJson(messageString, Message.class);
                if ("CONNECT_REQUEST".equals(message.getType())) {
                    String senderLogin = message.getSenderLogin();
                    System.out.printf("Клієнт %s хоче підключитися. Підтвердити підключення? (yes/no)\n", senderLogin);
                    String userResponse = reader.readLine();
                    Message responseMessage;
                    if ("yes".equalsIgnoreCase(userResponse)) {
                        responseMessage = new Message("CONNECT_ACCEPTED", message.getTargetIP(), message.getTargetPort());
                        sendMessageToServer(responseMessage);
                        System.out.println("Підключення підтверджено для клієнта " + senderLogin);
                    } else {
                        responseMessage = new Message("CONNECT_DENIED", message.getTargetIP(), message.getTargetPort());
                        sendMessageToServer(responseMessage);
                        System.out.printf("Підключення відхилено для клієнта %s\n", senderLogin);
                    }
                }
                else if ("EXECUTE".equals(message.getType())){
                    String commandResult = CommandExecutor.executeCommand(message.getMessage());
                    System.out.println("Виконано команду: " + message.getMessage());
                    System.out.println(commandResult);
                    sendMessageToServer(new Message("COMMAND_RESULT", message.getTargetIP(), message.getTargetPort(), null, commandResult));
                }
            } catch (IOException e) {
                System.out.println("Очікування запитів на підключення...");
            }
        }
    }


    private void sendMessageToServer(Message message) {
        try {
            String jsonMessage = gson.toJson(message);
            out.println(jsonMessage);
        } catch (Exception e) {
            System.err.println("Не вийшло відправити повідомлення на сервер: " + e.getMessage());
        }
    }

    private String receiveMessageFromServer(int timeout) throws IOException {
        clientSocket.setSoTimeout(timeout);
        try {
            return in.readLine();
        } catch (SocketTimeoutException e) {
            return null;
        }
    }
}