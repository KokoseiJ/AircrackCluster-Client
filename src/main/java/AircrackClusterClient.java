import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.Buffer;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AircrackClusterClient {
    public static double runAircrackBench() throws IOException, InterruptedException {
        Process aircrack;
        InputStream aircrackInputStream;
        byte[] aircrackBenchResultBytes;
        String aircrackBenchResult;
        String[] aircrackResultSplit;
        String aircrackLastResult;

        if(System.getProperty("os.name").equalsIgnoreCase("Linux"))
            aircrack = new ProcessBuilder("aircrack-ng", "-S", "-Z", "5").start();
        else aircrack = new ProcessBuilder("C:\\aircrack-ng\\bin\\aircrack-ng.exe", "-S", "-Z", "5").start();

        aircrackInputStream = aircrack.getInputStream();
        aircrack.waitFor();

        aircrackBenchResultBytes = new byte[aircrackInputStream.available()];
        if(aircrackInputStream.read(aircrackBenchResultBytes) == -1) return -1;
        aircrackBenchResult = new String(aircrackBenchResultBytes);
        aircrackResultSplit = aircrackBenchResult.split("k/s\\s+\r");
        aircrackLastResult = aircrackResultSplit[aircrackResultSplit.length - 2];

        return Double.parseDouble(aircrackLastResult);
    }

    public static boolean sendBenchResult(Socket socket, Double benchResult) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        writer.println("BENCH OK," + benchResult.intValue());
        System.out.println("Sent bench, waiting for the response...");
        String serverResponse = reader.readLine();
        System.out.println(serverResponse);
        return serverResponse.equals("BENCH_RESULT OK");
    }

    public static File receiveFile(String filename, Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        InputStream socketInputStream = socket.getInputStream();
        Scanner reader = new Scanner(socketInputStream);

        File file = new File(filename);
        FileOutputStream fileOutputStream = new FileOutputStream(file);

        long fileLength;
        int receivedSize = 0;
        long receivedSizeAll = 0;
        byte[] buffer = new byte[4096];

        System.out.println("Sending FILE_BYTE OK");
        writer.println("FILE_BYTE OK");
        fileLength = reader.nextLong();
        System.out.println("FileSize: " + fileLength + "bytes.");
        writer.println("FILE_READY OK");

        while(receivedSizeAll < fileLength) {
            if((receivedSize = socketInputStream.read(buffer)) == -1) break;
            receivedSizeAll += receivedSize;
            fileOutputStream.write(buffer, 0, receivedSize);
        }
        fileOutputStream.close();

        if(receivedSize != fileLength) {
            writer.println("FILE_RECV FAIL");
            System.err.print("Failed to receive file. File has been corrupted. ");
            if(reader.next().equals("RETRY")) {
                System.err.println("Retrying...");
                if(!file.delete()) System.err.println("Warning: Failed to delete file.");
                return receiveFile(filename, socket);
            }
            else if(reader.next().equals("DROP"))
                return null;
        }
        writer.println("FILE_RECV OK");

        System.out.println("Captured file successfully received!");
        return file;
    }

    public static String[] receiveInfo(Socket socket) throws IOException {
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        String receivedValue;

        System.out.println("Sending INF_READY OK");
        writer.println("INF_READY OK");
        System.out.println("Getting info");

        receivedValue = reader.readLine();

        return receivedValue.split(",");
    }

    public static String[] runAircrack(File capFile, File dictFile, String bssId, String essId)
            throws IOException, InterruptedException {
        String aircrackPath;
        Process aircrack;
        StringBuffer stringBuffer;
        BufferedReader bufferedReader;
        String buffer;
        String resultString;
        Pattern foundPattern, notFoundPattern;

        foundPattern = Pattern.compile("KEY FOUND! \\[ (.+?) ]");
        notFoundPattern = Pattern.compile("KEY NOT FOUND");

        if(System.getProperty("os.name").equalsIgnoreCase("Linux"))
            aircrackPath = "aircrack-ng";
        else aircrackPath = "C:\\aircrack-ng\\bin\\aircrack-ng.exe";
        aircrack = new ProcessBuilder(
                aircrackPath,
                "-b", bssId,
                "-e", essId,
                "-w", dictFile.getAbsolutePath(),
                capFile.getAbsolutePath()
        ).start();

        stringBuffer = new StringBuffer();
        bufferedReader = new BufferedReader(new InputStreamReader(aircrack.getInputStream()));

        while((buffer = bufferedReader.readLine()) != null) {
            stringBuffer.append(buffer);
        }

        Matcher matcher = foundPattern.matcher(stringBuffer.toString());
        System.out.println(matcher.find());
        System.out.println(matcher.group(1));

        return null;
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        runAircrack(new File("capFile.cap"), new File("testdict_short"), "00:07:89:56:3a:56", "olleh_WiFi_3A53");
        System.exit(0);

        InetAddress socketAddr;
        int socketPort;
        Socket socket;

        double benchResult;

        String[] ids;
        String bssId, essId;

        try {
            socketAddr = InetAddress.getByName(args[0]);
            if(args.length == 1)
                socketPort = 6974;
            else
                socketPort = Integer.parseInt(args[1]);
        } catch(Exception e) {
            System.err.println("Failed to parse arguments.");
            e.printStackTrace();
            System.exit(1);
            return;
        }
        try {
            System.out.println("Creating socket...");
            socket = new Socket(socketAddr, socketPort);
        } catch(IOException e) {
            System.err.println("Failed to create a socket.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Running bench...");
            benchResult = runAircrackBench();
            System.out.println(benchResult);
        } catch(IOException | InterruptedException e) {
            System.err.println("Failed to get Benchmark result.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Sending bench result...");
            if (!sendBenchResult(socket, benchResult)) {
                System.err.println("Server has returned unexpected value.");
                System.exit(1);
                return;
            }
        } catch(IOException e) {
            System.err.println("An error has been occurred while sending Benchmark result.");
            e.printStackTrace();
            System.exit(1);
            return;
        }

        try {
            System.out.println("Receiving capfile...");
            if(receiveFile("capFile.cap", socket) == null) {
                System.err.println("Server has refused to retry. Terminating program...");
                System.exit(1);
            }
        } catch(IOException e) {
            System.err.println("An error has been occurred while receiving file.");
            e.printStackTrace();
            System.exit(1);
        }

        try {
            System.out.println("Receiving bss/essid...");
            ids = receiveInfo(socket);
            bssId = ids[0];
            essId = ids[1];
            System.out.println(bssId + ", " + essId);
        } catch(IOException e) {
            System.err.println("An error has been occurred while receiving info.");
            e.printStackTrace();
            System.exit(1);
        }

        socket.close();
    }
}
