import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Objects;

public class TCPend {
    private static Integer PORT;
    private static String REMOTE_IP;
    private static Integer REMOTE_PORT;
    private static String FILE_NAME;
    private static Integer MTU;
    private static Integer WIN_SIZE;
    
    private static State STATE;

    public static void main(String[] args) {
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-p" -> { PORT = handleIntInput(args[i+1]); i++; }
                    case "-s" -> { REMOTE_IP = args[i+1]; i++; }
                    case "-a" -> { REMOTE_PORT = handleIntInput(args[i+1]); i++; }
                    case "-f" -> { FILE_NAME = args[i+1]; i++; }
                    case "-m" -> { MTU = handleIntInput(args[i+1]); i++; }
                    case "-c" -> { WIN_SIZE = handleIntInput(args[i+1]); i++; }
                    default -> {
                        System.out.println("Invalid argument '" + args[i] + "'");
                        return;
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            System.out.println("Argument not specified for given flag.");
            return;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return;
        }

        // Decide whether we are running in Sender mode or Receiver mode
        if (Objects.nonNull(REMOTE_IP)) {
            STATE = State.SEND;
            try {
                verifySenderAttributes();
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return;
            }
        } else {
            STATE = State.RECV;
            try {
                verifyReceiverAttributes();
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return;
            }
        }

        // Start Sender/Receiver
        if (STATE.equals(State.SEND)) {
            startSender();
        } else {
            startReceiver();
        }
    }

    private static void startSender() {
        InetAddress ip;
        try {
            ip = InetAddress.getByName(REMOTE_IP);
        } catch (UnknownHostException e) {
            System.out.println("Unable to resolve address '" + REMOTE_IP + "'");
            return;
        }
        Sender s = new Sender(PORT, ip, REMOTE_PORT, FILE_NAME, MTU, WIN_SIZE);
    }

    private static void startReceiver() {
        Receiver r = new Receiver(PORT, MTU, WIN_SIZE, FILE_NAME);
    }

    private static int handleIntInput(String s) {
        int out;
        try {
            out = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Unable to parse string '" + s + "'");
        }
        return out;
    }

    private enum State {
        RECV,
        SEND
    }

    private static void verifySenderAttributes() {
        ArrayList<String> missingArgs = new ArrayList<>();
        if (Objects.isNull(PORT)) {
            missingArgs.add("PORT");
        }
        if (Objects.isNull(REMOTE_PORT)) {
            missingArgs.add("REMOTE_PORT");
        }
        if (Objects.isNull(FILE_NAME)) {
            missingArgs.add("FILE_NAME");
        }
        if (Objects.isNull(MTU)) {
            missingArgs.add("MTU");
        }
        if (Objects.isNull(WIN_SIZE)) {
            missingArgs.add("WIN_SIZE");
        }

        if (!missingArgs.isEmpty()) {
            String msg = "Missing arguments to start Sender: ";
            msg += String.join(", ", missingArgs);
            throw new RuntimeException(msg);
        }
    }

    private static void verifyReceiverAttributes() {
        ArrayList<String> missingArgs = new ArrayList<>();
        if (Objects.isNull(PORT)) {
            missingArgs.add("PORT");
        }
        if (Objects.isNull(MTU)) {
            missingArgs.add("MTU");
        }
        if (Objects.isNull(WIN_SIZE)) {
            missingArgs.add("WIN_SIZE");
        }
        if (Objects.isNull(FILE_NAME)) {
            missingArgs.add("FILE_NAME");
        }

        if (!missingArgs.isEmpty()) {
            String msg = "Missing arguments to start Receiver: ";
            msg += String.join(", ", missingArgs);
            throw new RuntimeException(msg);
        }
    }
}