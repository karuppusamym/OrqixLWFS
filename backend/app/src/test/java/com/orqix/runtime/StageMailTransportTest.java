package com.orqix.runtime;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import com.orqix.runtime.internal.StageMailTransport;

class StageMailTransportTest {
    @Test void deliversRealSmtpTextWithStableMessageIdAndRejectsHeaderInjection() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            var captured=CompletableFuture.supplyAsync(()-> {
                try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    var output=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
                    output.print("220 capture ESMTP\r\n");output.flush();StringBuilder message=new StringBuilder();boolean data=false;
                    for(String line;(line=input.readLine())!=null;) {
                        if(data) {
                            if(".".equals(line)) {data=false;output.print("250 Captured\r\n");}
                            else {message.append(line).append("\r\n");continue;}
                        } else if(line.startsWith("EHLO")||line.startsWith("HELO"))output.print("250 capture\r\n");
                        else if(line.startsWith("MAIL")||line.startsWith("RCPT")||line.startsWith("RSET"))output.print("250 OK\r\n");
                        else if(line.equals("DATA")) {data=true;output.print("354 Send\r\n");}
                        else if(line.equals("QUIT")) {output.print("221 Bye\r\n");output.flush();break;}
                        else output.print("500 Unsupported\r\n");
                        output.flush();
                    }
                    return message.toString();
                } catch(IOException e) {throw new UncheckedIOException(e);}
            });
            var transport=new StageMailTransport(true,"localhost",server.getLocalPort(),"workflow@example.invalid","","",false);
            UUID id=UUID.randomUUID();transport.send(id,"capture@example.invalid","Approved $1","Hello <tag> — plain text");
            var mime=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(captured.get(10,TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
            assertThat(mime.getMessageID()).isEqualTo("<"+id+"@orqix.local>");
            assertThat(mime.getSubject()).isEqualTo("Approved $1");assertThat(mime.getContent().toString()).contains("Hello <tag> — plain text");
            assertThat(mime.getContentType()).startsWith("text/plain");
            assertThatThrownBy(()->transport.send(id,"capture@example.invalid","Hello\r\nBcc: other@example.invalid","body"))
                    .isInstanceOf(jakarta.mail.MessagingException.class);
        }
    }
}
