package vn.ktd.taskhub.client.feature.workload;

import com.google.gson.*;
import java.awt.*;
import java.time.LocalDate;
import javax.swing.*;
import vn.ktd.taskhub.client.feature.TeamPanel;

public final class WorkloadPanel extends TeamPanel {
    private final JTextField date=new JTextField(LocalDate.now().toString(),10), capacity=new JTextField(5);
    private final JComboBox<String> period=new JComboBox<>(new String[]{"Tuần","Tháng"});
    private final JTextField title=new JTextField(16),hours=new JTextField(5),due=new JTextField(LocalDate.now().plusDays(1).toString(),10);
    private final JTextField receiver=new JTextField(5);
    private final JLabel preview=new JLabel(" ");
    private final Grid loads=new Grid(new String[]{"userId","name","openTasks","usedHours","capacity","remainingHours","loadPct","color"},
            "Mã","Thành viên","Việc mở","Giờ đã giao","Quỹ giờ","Giờ còn","% tải","Mức tải");
    private final Grid moves=new Grid(new String[]{"taskId","title","fromId","assigneeId","hours","deadline"},
            "Mã việc","Công việc","Từ người","Sang người","Giờ","Hạn");
    private final Grid candidates=new Grid(new String[]{"userId","name","remainingHours","openTasks","nearby","score"},
            "Mã","Ứng viên","Giờ còn","Việc mở","Hạn gần","Điểm");
    private JsonObject move,simulated;
    private String confirmationId;
    private final JButton confirm=button("Xác nhận giao việc",this::confirm);
    public WorkloadPanel(Caller caller,long group,boolean leader) {
        super(caller,group,leader);
        add(stack(line("Kỳ",period,"Ngày trong kỳ",date,button("Xem tải",this::refresh)),
                line("Quỹ giờ của tôi",capacity,button("Lưu quỹ giờ",()->{
                    var d=base();d.addProperty("hours",capacity.getText());caller.send("CAPACITY_SAVE",d,r->refresh());
                }))),BorderLayout.NORTH);
        var tabs=new JTabbedPane();tabs.addTab("Mức tải",loads);
        if(leader) {
            var balances=new JPanel(new BorderLayout());balances.add(moves);
            balances.add(button("Mô phỏng chuyển việc đã chọn",()->{
                var sel=moves.selected();if(sel==null)return;move=sel;title.setText(sel.get("title").getAsString());
                hours.setText(sel.get("hours").getAsString());due.setText(sel.get("deadline").getAsString());
                receiver.setText(sel.get("assigneeId").getAsString());simulate();
            }),BorderLayout.SOUTH); tabs.addTab("Cân bằng",balances);
            var ranked=new JPanel(new BorderLayout());ranked.add(candidates);
            ranked.add(button("Chọn người nhận",()->{
                var sel=candidates.selected();if(sel!=null){receiver.setText(sel.get("userId").getAsString());simulate();}
            }),BorderLayout.SOUTH);tabs.addTab("Ứng viên",ranked);
            add(stack(line("Tên việc",title,"Giờ dự kiến",hours,"Hạn (yyyy-MM-dd)",due),
                    line("Mã người nhận",receiver,button("Gợi ý người nhận",()->caller.send("WORKLOAD_SUGGEST",draft(),r->{
                        candidates.showRows(r.getAsJsonArray("candidates"));tabs.setSelectedComponent(ranked);
                    })),button("Mô phỏng",this::simulate),confirm,button("Bỏ bản nháp",this::clear)),
                    line(preview)),BorderLayout.SOUTH);
        }
        add(tabs);confirm.setEnabled(false);refresh();
    }
    private JsonObject base(){var d=input();d.addProperty("period",period.getSelectedIndex()==0?"week":"month");d.addProperty("date",date.getText());return d;}
    private JsonObject draft(){
        var d=base();d.addProperty("title",title.getText());d.addProperty("hours",hours.getText());
        d.addProperty("assigneeId",receiver.getText());d.addProperty("deadline",due.getText());
        if(move!=null){d.add("taskId",move.get("taskId"));d.add("fromId",move.get("fromId"));}
        return d;
    }
    private void refresh(){caller.send("WORKLOAD_VIEW",base(),r->{
        loads.showRows(r.getAsJsonArray("members"));
        if(leader){moves.showRows(r.getAsJsonArray("suggestedMoves"));
            preview.setText(r.getAsJsonArray("suggestedMoves").isEmpty()?"Nhóm đang cân bằng.":"Có đề xuất trong thẻ Cân bằng.");}
    });}
    private void simulate(){
        var request=draft();caller.send("WORKLOAD_SIMULATE",request,r->{
            var s=r.getAsJsonObject("simulation");
            preview.setText(val(s,"name")+"   ·   "+val(s,"usedHours")+" → "+val(s,"afterHours")+" giờ   ·   "
                    +val(s,"loadPct")+"% → "+val(s,"afterPct")+"%");
            if(!request.equals(simulated))confirmationId=java.util.UUID.randomUUID().toString();
            simulated=request;confirm.setEnabled(true);
        });
    }
    private void confirm(){
        var d=draft();
        if(!d.equals(simulated)){preview.setText("Bản nháp đã thay đổi. Hãy mô phỏng lại.");confirm.setEnabled(false);return;}
        if(JOptionPane.showConfirmDialog(this,"Lưu phân công cho "+title.getText()+"?","Giao việc",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        confirm.setEnabled(false);
        d.addProperty("confirmationId",confirmationId);
        caller.send("TASK_ASSIGN_CONFIRM",d,r->{clear();refresh();JOptionPane.showMessageDialog(this,"Đã giao việc #"+r.get("taskId"));});
    }
    private void clear(){move=null;simulated=null;title.setText("");hours.setText("");receiver.setText("");confirm.setEnabled(false);preview.setText(" ");}
}
