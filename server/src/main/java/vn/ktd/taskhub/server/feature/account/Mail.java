package vn.ktd.taskhub.server.feature.account;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;
import java.util.concurrent.*;

public final class Mail {
    private static final ExecutorService sender=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256),new ThreadPoolExecutor.AbortPolicy());
    public static boolean configured(){return System.getenv("KTD_SMTP_HOST")!=null && System.getenv("KTD_MAIL_FROM")!=null;}
    public static void send(String address,String text){
        if(!configured()){System.err.println("SMTP chưa được cấu hình.");return;}
        try { sender.execute(()->deliver(address,text)); }
        catch(RejectedExecutionException busy){System.err.println("Hàng đợi email đầy.");}
    }
    private static void deliver(String address,String text){
        var p=new Properties();
        p.setProperty("mail.smtp.host",System.getenv("KTD_SMTP_HOST"));
        p.setProperty("mail.smtp.port",System.getenv().getOrDefault("KTD_SMTP_PORT","587"));
        String user=System.getenv("KTD_SMTP_USER");
        p.setProperty("mail.smtp.starttls.enable","true");
        p.setProperty("mail.smtp.starttls.required","true");
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.auth",String.valueOf(user!=null));
        for(String k:new String[]{"connectiontimeout","timeout","writetimeout"})p.setProperty("mail.smtp."+k,"10000");
        var session=Session.getInstance(p);
        try{
            var message=new MimeMessage(session);
            message.setFrom(new InternetAddress(System.getenv("KTD_MAIL_FROM"),true));
            message.setRecipient(Message.RecipientType.TO,new InternetAddress(address,true));
            message.setSubject("KTD Task Hub – tài khoản","UTF-8");
            message.setText(text,"UTF-8");
            Transport.send(message,user,System.getenv("KTD_SMTP_PASSWORD"));
        }catch(MessagingException e){System.err.println("Gửi email KTD thất bại: "+e.getClass().getSimpleName());}
    }
}
