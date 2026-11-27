package vn.ktd.taskhub.client;

import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class AccountDesktop {
    private record Form(String label,String action,String... fields){public String toString(){return label;}}
    private static final Form[] PUBLIC={
        new Form("Đăng nhập","LOGIN","studentId","password"),
        new Form("Đăng ký","REGISTER","studentId","email","fullName","birthDate","password","confirmation"),
        new Form("Xác minh đăng ký","VERIFY_OTP","challengeId","code"),
        new Form("Gửi lại mã đăng ký","OTP_RESEND","requestId","password"),
        new Form("Quên mật khẩu / gửi lại mã","RESET_REQUEST","studentId","email"),
        new Form("Đặt lại mật khẩu","VERIFY_OTP","challengeId","code","password","confirmation")
    };
    private static final Form[] PRIVATE={
        new Form("Xem hồ sơ","PROFILE_GET"),
        new Form("Sửa họ tên, ngày sinh","PROFILE_UPDATE","fullName","birthDate"),
        new Form("Đổi email / gửi lại mã","EMAIL_CHANGE","email","password"),
        new Form("Xác minh email mới","VERIFY_OTP","challengeId","code"),
        new Form("Đổi mật khẩu","CHANGE_PASSWORD","oldPassword","newPassword","confirmation"),
        new Form("Tra nhật ký","AUDIT_LIST","groupId","from","to","actorId","action","targetId","beforeId"),
        new Form("Xuất CSV của nhóm","AUDIT_EXPORT","groupId","from","to","actorId","action","targetId"),
        new Form("Đăng xuất","LOGOUT")
    };
    private static final Map<String,String> LABEL=Map.ofEntries(
        Map.entry("studentId","MSSV"),Map.entry("password","Mật khẩu"),Map.entry("oldPassword","Mật khẩu hiện tại"),
        Map.entry("newPassword","Mật khẩu mới"),Map.entry("confirmation","Nhập lại mật khẩu mới"),Map.entry("email","Email"),
        Map.entry("birthDate","Ngày sinh (dd/MM/yyyy)"),Map.entry("fullName","Họ tên"),Map.entry("requestId","Mã hồ sơ đăng ký"),
        Map.entry("challengeId","Mã yêu cầu trong email"),Map.entry("code","OTP 6 chữ số"),Map.entry("groupId","Mã nhóm (trống = cá nhân)"),
        Map.entry("from","Từ ngày giờ (yyyy-MM-dd HH:mm:ss)"),Map.entry("to","Đến ngày giờ"),Map.entry("actorId","Mã người thực hiện"),
        Map.entry("action","Loại hành động"),Map.entry("targetId","Mã đối tượng"),Map.entry("beforeId","Trước mã nhật ký (trang sau)"));
    private final JFrame frame=new JFrame("KTD Task Hub – Tài khoản và nhật ký");
    private final JComboBox<Form> menu=new JComboBox<>(PUBLIC);
    private final JPanel fields=new JPanel(new GridLayout(0,2,8,8));
    private final Map<String,JTextField> inputs=new LinkedHashMap<>();
    private final JTextArea result=new JTextArea(11,55);
    private final JButton submit=new JButton("Thực hiện");
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Properties settings=new Properties();
    private Socket socket;
    private boolean authenticated;

    public static void main(String[] args){SwingUtilities.invokeLater(()->new AccountDesktop().show());}
    private void show(){
        try(var in=AccountDesktop.class.getResourceAsStream("/client.properties")){settings.load(in);}
        catch(Exception ex){JOptionPane.showMessageDialog(frame,"Không đọc được client.properties");return;}
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        var body=new JPanel(new BorderLayout(8,8));
        body.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));
        var top=new JPanel(new BorderLayout(8,8));top.add(menu,BorderLayout.NORTH);top.add(fields);top.add(submit,BorderLayout.SOUTH);
        result.setEditable(false);result.setLineWrap(true);result.setWrapStyleWord(true);
        body.add(top,BorderLayout.NORTH);body.add(new JScrollPane(result));frame.setContentPane(body);
        menu.addActionListener(e->form());submit.addActionListener(e->send());form();
        frame.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){worker.execute(()->disconnect());worker.shutdown();}});
        worker.scheduleWithFixedDelay(()->{
            if(authenticated)try{
                var reply=ClientMain.exchange(socket,"HEARTBEAT",new JsonObject());
                if(!reply.get("ok").getAsBoolean())throw new IOException();
            }catch(IOException ex){disconnect();render(false,"Phiên đã kết thúc. Vui lòng đăng nhập lại.");}
        },30,30,TimeUnit.SECONDS);
        frame.pack();frame.setLocationByPlatform(true);frame.setVisible(true);
    }
    private void form(){
        var f=(Form)menu.getSelectedItem();inputs.clear();fields.removeAll();
        if(f!=null)for(String key:f.fields){
            JTextField input=key.toLowerCase(Locale.ROOT).contains("password")||key.equals("confirmation")||key.equals("code")?new JPasswordField(22):new JTextField(22);
            fields.add(new JLabel(LABEL.get(key)));fields.add(input);inputs.put(key,input);
        }
        fields.revalidate();frame.pack();
    }
    private void disconnect(){authenticated=false;if(socket!=null)try{socket.close();}catch(IOException ignored){}socket=null;}
    private void render(boolean logged,String message){SwingUtilities.invokeLater(()->{
        if(menu.getItemAt(0)!=(logged?PRIVATE:PUBLIC)[0])menu.setModel(new DefaultComboBoxModel<>(logged?PRIVATE:PUBLIC));
        form();result.setText(message);submit.setEnabled(true);menu.setEnabled(true);
    });}
    private void send(){
        var f=(Form)menu.getSelectedItem();var data=new JsonObject();
        inputs.forEach((k,v)->data.addProperty(k,v.getText()));
        if(f.action.equals("VERIFY_OTP"))data.addProperty("purpose",f==PUBLIC[2]?"register":f==PUBLIC[5]?"reset_password":"change_email");
        if(f.action.equals("REGISTER")){
            String id=data.get("studentId").getAsString(), pw=data.get("password").getAsString();
            if(!vn.ktd.taskhub.shared.dto.AccountRules.student(id)
                    || !vn.ktd.taskhub.shared.dto.AccountRules.email(data.get("email").getAsString())
                    || data.get("fullName").getAsString().isBlank()){
                result.setText("Kiểm tra họ tên, địa chỉ email và MSSV (6–12 chữ hoặc số).");return;
            }
            try { vn.ktd.taskhub.shared.dto.AccountRules.birthDate(data.get("birthDate").getAsString()); }
            catch(IllegalArgumentException invalid){result.setText("Nhập ngày sinh dd/MM/yyyy, đủ 16 tuổi.");return;}
            if(!vn.ktd.taskhub.shared.dto.AccountRules.password(pw,id) || !pw.equals(data.get("confirmation").getAsString())){
                result.setText("Mật khẩu cần 8–32 ký tự, có chữ hoa, thường, số và ký tự đặc biệt. Không dùng khoảng trắng hoặc MSSV. Hai lần nhập phải giống nhau.");return;
            }
        }
        Path destination=null;
        if(f.action.equals("AUDIT_EXPORT")){
            var picker=new JFileChooser();picker.setSelectedFile(new File("nhat-ky.csv"));
            if(picker.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
            destination=picker.getSelectedFile().toPath();
            if(Files.exists(destination)){result.setText("Hãy chọn tên tệp mới.");return;}
        }
        final Path csvPath=destination;
        menu.setEnabled(false);submit.setEnabled(false);
        worker.execute(()->{
            try{
                if(socket==null){
                    socket=new Socket();socket.connect(new InetSocketAddress(settings.getProperty("server.host"),Integer.parseInt(settings.getProperty("server.port"))),5000);
                    socket.setSoTimeout(15000);
                }
                var reply=ClientMain.exchange(socket,f.action,data);
                boolean ok=reply.get("ok").getAsBoolean();
                String message;
                if(ok){
                    var payload=reply.getAsJsonObject("data");
                    if(f.action.equals("LOGIN")){authenticated=true;payload.remove("sessionToken");}
                    if(f.action.equals("LOGOUT")||f.action.equals("CHANGE_PASSWORD"))disconnect();
                    if(csvPath!=null){
                        Files.writeString(csvPath,"\uFEFF"+payload.get("csv").getAsString(),StandardOpenOption.CREATE_NEW);
                        message="Đã lưu "+csvPath;
                    }else message=payload.size()==0?"Thành công.":new GsonBuilder().setPrettyPrinting().create().toJson(payload);
                }else{
                    message=reply.get("error").getAsString();
                    if(message.equals("LOGIN_FAILED"))message="Không thể đăng nhập với thông tin đã cung cấp.";
                    if(message.equals("SESSION_EXPIRED"))disconnect();
                    if(reply.has("data"))message+=" "+reply.get("data");
                }
                if(!authenticated)disconnect();
                render(authenticated,message);
            }catch(Exception ex){disconnect();render(false,"Không hoàn tất thao tác: "+ex.getMessage());}
        });
    }
}

