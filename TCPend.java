import java.net.*;

/**
 * 
 */
public class TCPend {
    public static void main(String[] args) {
        System.out.println("hi");

        if (args.length < 1) {
            System.err.println("Error: missing or additional arguments");
            return;
        }

        String flag = args[2];
        if (flag.equals("-s"))
            runSender(args);
        else if (flag.equals("-m"))
            runReceiver(args);
        else
            System.err.println("Second flag -s implies sender, while -m implies receiver.");
        return;
    }

    /**
     * 
     * @param args
     */
    private static void runSender(String[] args) {

        if (args.length != 12) {
            System.err.println("Error: missing or additional arguments");
            return;
        }
        int clientPort;     // port client runs on
        InetAddress IPAddr; // IP of remote peer
        int remotePort;     // port of remote peer
        String filename;    // file to be sent
        int mtu;            // max transmission unit (in bytes)
        int windowSize;     // sliding window size (in segments)
        
        // more argument checking over flags?
        try {
            clientPort = Integer.parseInt(args[1]);
            IPAddr = InetAddress.getByName(args[3]);
            remotePort = Integer.parseInt(args[5]);
            filename = args[7];
            mtu = Integer.parseInt(args[9]);
            windowSize = Integer.parseInt(args[11]);
        } catch (NumberFormatException e) {
            System.err.println("Port / mtu / window size must be an integer.");
            return;
        } catch (UnknownHostException e) {
            System.err.println("IP address must be an integer.");
            return;
        }

        System.out.println("Running...");
        Sender s = new Sender(clientPort, IPAddr, remotePort, filename, mtu, windowSize);
    }

    /**
     * 
     * @param args
     */
    private static void runReceiver(String[] args) {

        if (args.length != 8) {
            System.err.println("Error: missing or additional arguments");
            return;
        }

        int clientPort;     // port client runs on
        int mtu;            // max transmission unit (in bytes)
        int windowSize;     // sliding window size (in segments)
        String filename;    // file to be written to

        try {
            clientPort = Integer.parseInt(args[1]);
            mtu = Integer.parseInt(args[3]);
            windowSize = Integer.parseInt(args[5]);
            filename = args[7];
        } catch (NumberFormatException e) {
            System.err.println("Port / mtu / window size must be an integer");
            return;
        }
        Receiver r = new Receiver(clientPort, mtu, windowSize, filename);
        
    }

}