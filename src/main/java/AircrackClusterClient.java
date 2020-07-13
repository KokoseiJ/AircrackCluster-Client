import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AircrackClusterClient {
    public static double runAircrackBench(StringBuilder prefixArgs) throws IOException, InterruptedException {
        Process aircrack;
        InputStream aircrackInputStream;
        byte[] aircrackBenchResultBytes;
        String aircrackBenchResult;
        String[] aircrackResultSplit;
        String aircrackLastResult;

        aircrack = new ProcessBuilder("aircrack-ng", prefixArgs.toString(), "-S", "-Z", "5").start();

        aircrackInputStream = aircrack.getInputStream();
        aircrack.waitFor();

        aircrackBenchResultBytes = new byte[aircrackInputStream.available()];
        if (aircrackInputStream.read(aircrackBenchResultBytes) == -1) return -1;
        aircrackBenchResult = new String(aircrackBenchResultBytes);
        aircrackResultSplit = aircrackBenchResult.split("k/s\\s+\r");
        aircrackLastResult = aircrackResultSplit[aircrackResultSplit.length - 2];

        return Double.parseDouble(aircrackLastResult);
    }

    public static boolean sendBenchResult(Socket socket, Double benchResult) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        writer.println("BENCH OK," + benchResult.intValue());
        System.out.print("Sending bench result, waiting for the response... ");
        String serverResponse = reader.readLine();
        return serverResponse.equals("BENCH_RESULT OK");
    }

    public static File receiveFile(String filename, Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        InputStream socketInputStream = socket.getInputStream();
        Scanner reader = new Scanner(socketInputStream);

        File file = new File(filename);
        FileOutputStream fileOutputStream = new FileOutputStream(file);

        long fileLength;
        int receivedSize;
        long receivedSizeAll = 0;
        byte[] buffer = new byte[4096];

        writer.println("FILE_BYTE OK");
        fileLength = reader.nextLong();
        System.out.println("FileSize: " + fileLength + "byte.");
        writer.println("FILE_READY OK");

        while (receivedSizeAll < fileLength) {
            if ((receivedSize = socketInputStream.read(buffer)) == -1) break;
            receivedSizeAll += receivedSize;
            fileOutputStream.write(buffer, 0, receivedSize);
        }
        fileOutputStream.close();

        if (receivedSizeAll != fileLength) {
            writer.println("FILE_RECV FAIL");
            System.err.print("Failed to receive file. File has been corrupted. ");
            if (reader.next().equals("RETRY")) {
                System.err.println("Retrying...");
                if (!file.delete()) System.err.println("Warning: Failed to delete file.");
                return receiveFile(filename, socket);
            } else if (reader.next().equals("DROP"))
                return null;
        }
        writer.println("FILE_RECV OK");

        System.out.println("Captured file successfully received!\n");
        return file;
    }

    public static String[] receiveInfo(Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        String receivedValue;

        writer.println("INF_READY OK");
        receivedValue = reader.readLine();

        return receivedValue.split(",");
    }

    public static class StreamGetter extends Thread {
        InputStream inputStream;
        ByteArrayOutputStream byteStream;
        boolean isEnableStatusOutput;

        public StreamGetter(InputStream inputStream, boolean isEnableStatusOutput) {
            this.inputStream = inputStream;
            this.isEnableStatusOutput = isEnableStatusOutput;
        }

        public void run() {
            try {
                byteStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int readBytes;
                while ((readBytes = this.inputStream.read(buffer)) != -1) {
                    byteStream.write(buffer, 0, readBytes);
                    if (isEnableStatusOutput) System.out.println(new String(buffer, 0, readBytes));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public String getResult() {
            return byteStream.toString();
        }
    }

    public static Process runAircrack(File capFile, String bssId, String essId, StringBuilder prefixArgs)
            throws IOException {
        Process aircrack;

        aircrack = new ProcessBuilder(
                "aircrack-ng",
                prefixArgs.toString(),
                "-b", bssId,
                "-e", essId,
                "-w", "-",
                capFile.getAbsolutePath()
        ).start();
        return aircrack;
    }

    public static String runAircrackWithDataFromPipe(Socket socket, Process aircrack, boolean isEnableStatusOutput)
            throws IOException, InterruptedException {
        InputStream socketStream = socket.getInputStream();
        BufferedReader socketReader = new BufferedReader(new InputStreamReader(socketStream));
        PrintWriter socketWriter = new PrintWriter(socket.getOutputStream(), true);
        PrintWriter aircrackWriter = new PrintWriter(aircrack.getOutputStream(), true);
        Pattern foundPattern;
        Matcher matcher;
        StreamGetter thread;
        String buffer, result;
        int lines, i;

        foundPattern = Pattern.compile(".*?KEY FOUND! \\[ (.*?) ].*?");

        socketWriter.println("DICT_SIZE OK");
        lines = Integer.parseInt(socketReader.readLine());

        thread = new StreamGetter(aircrack.getInputStream(), isEnableStatusOutput);
        thread.start();

        socketWriter.println("DICT_READY OK");

        for (i = 0; i < lines; i++) {
            buffer = socketReader.readLine();
            aircrackWriter.println(buffer);
        }
        aircrackWriter.close();

        aircrack.waitFor();

        result = thread.getResult();

        matcher = foundPattern.matcher(result);
        if (matcher.matches()) return matcher.group(1);
        else return null;
    }

    public static boolean sendKey(Socket socket, String key) throws IOException {
        PrintWriter socketWriter = new PrintWriter(socket.getOutputStream(), true);

        if (key == null) {
            socketWriter.println("AIRCRACK_RESULT FAIL");
            return false;
        } else {
            socketWriter.println("AIRCRACK_RESULT OK," + key);
            return true;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Socket socket;
        InetAddress socketAddr;
        int socketPort;

        boolean isEnableStatusOutput = false;

        double benchResult;

        File capFile;

        String[] ids, newArgs = new String[2];
        String bssId, essId, password;
        StringBuilder prefixArgs = new StringBuilder();

        Process aircrack;

        if(!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            System.out.println("This operating system is not supported. Exit.");
            System.exit(0);
        }

        int argsCnt = 0;
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                newArgs[argsCnt++] = arg;
            } else {
                if (arg.equalsIgnoreCase("-V")) {
                    isEnableStatusOutput = true;
                    continue;
                }
                prefixArgs.append(arg).append(" ");
            }
        }

        try {
            socketAddr = InetAddress.getByName(newArgs[0]);
            if (newArgs[1] == null)
                socketPort = 6974;
            else
                socketPort = Integer.parseInt(newArgs[1]);
        } catch (Exception e) {
            System.err.println("Failed to parse arguments.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Creating socket...\n");
            socket = new Socket(socketAddr, socketPort);
        } catch (IOException e) {
            System.err.println("Failed to create a socket.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.print("Running bench... ");
            benchResult = runAircrackBench(prefixArgs);
            System.out.println(benchResult + " k/s");
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to get Benchmark result.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            if (!sendBenchResult(socket, benchResult)) {
                System.err.println("Server has returned unexpected value.");
                System.exit(1);
                return;
            }
            System.out.println("OK\n");
        } catch (IOException e) {
            System.err.println("An error has been occurred while sending Benchmark result.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Receiving capfile...");
            if ((capFile = (receiveFile("capFile.cap", socket))) == null) {
                System.err.println("Server has refused to retry. Terminating program...");
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("An error has been occurred while receiving file.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Receiving AP information...");
            ids = receiveInfo(socket);
            bssId = ids[0];
            essId = ids[1];
            System.out.println("BSSID: " + bssId + ", " + "ESSID: " + essId + "\n");
        } catch (IOException e) {
            System.err.println("An error has been occurred while receiving info.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        System.out.println("All requirements are transferred Successfully!");
        System.out.println("Receiving dictionary file...\n");

        int count = 0;
        do {
            count++;
            System.out.print("Trying " + count + "... ");
            aircrack = runAircrack(capFile, bssId, essId, prefixArgs);
            password = runAircrackWithDataFromPipe(socket, aircrack, isEnableStatusOutput);
            if (password == null)
                System.out.println("failed.\n");
            else
                System.out.println("Success!\nPassword: " + password + "\n");
        } while (!sendKey(socket, password));
        socket.close();
    }
}
