import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.app.AirPlay2Audio;
import wtf.nanoka.airplay.app.DesktopLifecycle;

/** Starts the packaged bean with real Bonjour, then closes the complete app. */
public class VerifyAirPlay2Desktop {
    public static void main(String[] args) throws Exception {
        var context = new SpringApplicationBuilder(PlayerApp.class).web(WebApplicationType.NONE)
            .headless(false).run("--java-airplay.validation=true", "--airplay.networkInterface=" + args[0],
                "--airplay.serverName=airplay Verification", "--airplay.airplay2.port=7002");
        try {
            AirPlay2Audio.setEnabled(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!AirPlay2Audio.state().equals("ready") && !AirPlay2Audio.state().equals("error")
                    && System.nanoTime() < deadline) Thread.sleep(100);
            if (!AirPlay2Audio.state().equals("ready")) throw new AssertionError(AirPlay2Audio.detail());
            try (Socket socket = new Socket(args[0], 7002)) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes());
                byte[] header = socket.getInputStream().readNBytes(15);
                if (!new String(header).contains("200")) throw new AssertionError("Audio control service did not respond");
            }
            String children = ProcessHandle.current().descendants().map(p -> Long.toString(p.pid())).reduce("", (a,b) -> a + b + "\n");
            Files.writeString(Path.of("maintenance/airplay2-desktop-child-pids.txt"), children);
            System.out.println("AIRPLAY2_PACKAGED_BEAN_MDNS_NETWORK_AND_DESKTOP_STARTUP_PASSED: " + AirPlay2Audio.detail());
            DesktopLifecycle.requestExit(context);
        } catch (Throwable error) { error.printStackTrace(); context.close(); System.exit(1); }
    }
}
