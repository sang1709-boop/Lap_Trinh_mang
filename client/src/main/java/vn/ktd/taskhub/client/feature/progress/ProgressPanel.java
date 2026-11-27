package vn.ktd.taskhub.client.feature.progress;

import com.google.gson.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Base64;
import javax.swing.*;
import vn.ktd.taskhub.client.feature.TeamPanel;

public final class ProgressPanel extends TeamPanel {
    private final JLabel alerts=new JLabel(), summary=new JLabel(), cutoff=new JLabel();
    private final JProgressBar byCount=new JProgressBar(0,100),byHours=new JProgressBar(0,100);
    private final Grid people=new Grid(new String[]{"name","membership","total","notStarted","inProgress","completed","cancelled","lateOpen","lateCompleted","countPct","hoursPct"},
            "Thành viên","Tư cách","Tổng","Chưa làm","Đang làm","Xong","Hủy","Đang trễ","Xong muộn","% việc","% giờ");
    private final Grid details=new Grid(new String[]{"task_id","title","full_name","task_status","estimated_hours","deadline","review_state","reviewLabel"},
            "Mã việc","Tên việc","Phụ trách","Trạng thái","Giờ","Hạn","Minh chứng","Tự duyệt");
    private final Grid parents=new Grid(new String[]{"task_id","title","full_name","deadline","label"},
            "Mã việc cha","Tên việc","Chủ việc","Hạn","Trạng thái");
    private final JTextField from=new JTextField(LocalDate.now().withDayOfMonth(1).toString(),10),
            to=new JTextField(LocalDate.now().toString(),10), member=new JTextField(5);
    private final JTabbedPane tabs=new JTabbedPane();
    private final Charts chart=new Charts();
    private JsonArray tasks=new JsonArray();
    public ProgressPanel(Caller caller,long group,boolean leader) {
        super(caller,group,leader);
        byCount.setStringPainted(true);byHours.setStringPainted(true);
        var actions=line(button("Tiến độ của tôi",()->load("PROGRESS_MINE")));
        if(leader)actions.add(button("Tiến độ nhóm",()->load("PROGRESS_GROUP")));
        add(stack(line(alerts),actions,line(summary,byCount,byHours),line(cutoff)),BorderLayout.NORTH);
        if(leader) {
            tabs.addTab("Thành viên",people);
            people.onOpen(()->{
                var person=people.selected();if(person==null)return;
                var selected=new JsonArray();
                for(var t:tasks)if(t.getAsJsonObject().get("owner").equals(person.get("userId")))selected.add(t);
                details.showRows(selected);tabs.setSelectedComponent(details);
            });
        }
        tabs.addTab("Việc lá",details);tabs.addTab("Cha chờ đóng",parents);tabs.addTab("Biểu đồ",chart);add(tabs);
        if(leader)add(stack(line("Từ ngày",from,"đến",to,"Mã thành viên (tùy chọn)",member),
                line(button("Xem báo cáo",()->load("REPORT_VIEW")),button("Xuất PDF",()->export("pdf")),
                        button("Xuất Word",()->export("docx")))),BorderLayout.SOUTH);
        load("PROGRESS_MINE");
    }
    private JsonObject report(){
        var d=input();d.addProperty("from",from.getText());d.addProperty("to",to.getText());d.addProperty("memberId",member.getText());return d;
    }
    private void load(String action){caller.send(action,action.equals("REPORT_VIEW")?report():input(),r->{
        var s=r.getAsJsonObject("summary");var a=r.getAsJsonObject("alerts");
        alerts.setText("Xanh "+a.get("safe")+"     Vàng "+a.get("warning")+"     Đỏ "+a.get("danger")
                +"     Chờ chấm / đạt "+a.get("pendingOrApproved")+"     Cha chờ đóng quá hạn "+a.get("overdueParents"));
        summary.setText("Hoàn thành "+s.get("completed")+"/"+s.get("total")+" việc");
        for(var key:new String[]{"countPct","hoursPct"}){
            var bar=key.equals("countPct")?byCount:byHours;var v=s.get(key);
            bar.setValue(v.isJsonNull()?0:(int)Math.round(v.getAsDouble()));
            bar.setString(v.isJsonNull()?"Chưa có việc":val(s,key)+(bar==byCount?"% số việc":"% số giờ"));
        }
        cutoff.setText("Mốc xét "+val(r,"asOf")+" (UTC+7)");
        var members=r.getAsJsonArray("members");
        for(var e:members){var m=e.getAsJsonObject();m.addProperty("membership",m.get("leftGroup").getAsBoolean()?"Đã rời nhóm":"Trong nhóm");}
        people.showRows(members);tasks=r.getAsJsonArray("tasks");
        for(var e:tasks){var t=e.getAsJsonObject();t.addProperty("reviewLabel","1".equals(t.get("self_review").getAsString())?"Tự duyệt":"");}
        details.showRows(tasks);var ready=r.getAsJsonArray("parentsToClose");
        for(var e:ready){var p=e.getAsJsonObject();p.addProperty("label",p.get("overdue").getAsBoolean()?"Quá hạn":"Chờ đóng");}
        parents.showRows(ready);chart.set(s);
        tabs.setSelectedComponent(action.equals("PROGRESS_MINE")?details:action.equals("REPORT_VIEW")?chart:people);
    });}
    private void export(String format){
        var picker=new JFileChooser();picker.setSelectedFile(new File("bao-cao-nhom-"+group+"."+format));
        if(picker.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path path=picker.getSelectedFile().toPath();
        if(Files.exists(path)){JOptionPane.showMessageDialog(this,"Hãy chọn tên tệp mới.");return;}
        var d=report();d.addProperty("format",format);caller.send("REPORT_EXPORT",d,r->{
            try {Files.write(path,Base64.getDecoder().decode(r.get("content").getAsString()),StandardOpenOption.CREATE_NEW);
                JOptionPane.showMessageDialog(this,"Đã lưu "+path);}
            catch(IOException ex){JOptionPane.showMessageDialog(this,"Không lưu được tệp: "+ex.getMessage());}
        });
    }
    private static final class Charts extends JPanel {
        private final String[] labels={"Chưa thực hiện","Đang thực hiện","Hoàn thành","Đã hủy"};
        private final Color[] colors={new Color(94,119,151),new Color(216,157,51),new Color(57,146,108),new Color(165,165,165)};
        private int[] values=new int[4];
        void set(JsonObject s){values=new int[]{s.get("notStarted").getAsInt(),s.get("inProgress").getAsInt(),
                s.get("completed").getAsInt(),s.get("cancelled").getAsInt()};repaint();}
        protected void paintComponent(Graphics g){
            super.paintComponent(g);
            int sum=java.util.Arrays.stream(values).sum(),max=java.util.Arrays.stream(values).max().orElse(0);
            int size=Math.min(160,Math.max(0,Math.min(getWidth()/3,getHeight()-30))),angle=0;
            for(int i=0;i<4;i++){
                int y=28+i*31;g.setColor(getForeground());g.drawString(labels[i]+" ("+values[i]+")",12,y);
                g.setColor(colors[i]);g.fillRect(145,y-12,max==0?0:values[i]*Math.max(0,getWidth()/2-145)/max,14);
                int arc=sum==0?0:(i==3?360-angle:(int)Math.round(360.0*values[i]/sum));
                g.fillArc(getWidth()*2/3,12,size,size,angle,arc);angle+=arc;
            }
        }
    }
}
