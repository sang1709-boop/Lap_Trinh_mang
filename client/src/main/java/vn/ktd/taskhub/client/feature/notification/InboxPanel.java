package vn.ktd.taskhub.client.feature.notification;

import com.google.gson.*;
import java.awt.*;
import java.util.function.IntConsumer;
import javax.swing.*;
import vn.ktd.taskhub.client.feature.TeamPanel;

public final class InboxPanel extends TeamPanel {
    private final JComboBox<String> status=new JComboBox<>(new String[]{"Tất cả","Chưa đọc","Đã đọc"});
    private final JTextField kind=new JTextField(12);
    private final Grid grid=new Grid(new String[]{"state","title","kind","createdAt","chatCount"},
            "Trạng thái","Tiêu đề","Loại","Thời điểm","Số tin chat");
    private final JButton next=button("Trang sau",()->load(true));
    private final IntConsumer badge;
    private final JLabel count=new JLabel();
    private Long before;
    public InboxPanel(Caller caller,IntConsumer badge) {
        super(caller,0,false);this.badge=badge;
        add(line(count,"Trạng thái",status,"Loại",kind,button("Lọc",this::refresh)),BorderLayout.NORTH);
        add(grid);grid.onOpen(this::open);
        add(line(button("Mở thông báo",this::open),button("Trang đầu",this::refresh),next),BorderLayout.SOUTH);
        next.setEnabled(false);
    }
    public void refresh(){load(false);}
    private void load(boolean more) {
        var d=new JsonObject();d.addProperty("kind",kind.getText());
        d.addProperty("status",new String[]{"all","unread","read"}[status.getSelectedIndex()]);
        if(more && before!=null)d.addProperty("beforeId",before);
        caller.send("NOTIFICATION_LIST",d,r->{
            int unread=r.get("unreadCount").getAsInt();badge.accept(unread);count.setText("Chưa đọc: "+unread);
            var rows=r.getAsJsonArray("notifications");
            for(var e:rows){var row=e.getAsJsonObject(); row.addProperty("state",row.get("isRead").getAsInt()==0?"Chưa đọc":"Đã đọc");}
            grid.showRows(rows);before=r.has("nextBeforeId")?r.get("nextBeforeId").getAsLong():null;next.setEnabled(before!=null);
        });
    }
    private void open(){
        var row=grid.selected();if(row==null)return;
        var d=new JsonObject();d.add("notificationId",row.get("id"));
        if(!row.get("lastMessageId").isJsonNull())d.add("seenMessageId",row.get("lastMessageId"));
        caller.send("NOTIFICATION_OPEN",d,r->{
            var n=r.getAsJsonObject("notification");refresh();
            String body=n.get("body").getAsString()+"\n\n"+val(n,"targetType")+" #"+val(n,"targetId");
            if(r.has("task")){
                var t=r.getAsJsonObject("task");
                body+="\n"+val(t,"title")+"\nPhụ trách: "+val(t,"assignee")+" · "+val(t,"status")
                        +"\nHạn: "+val(t,"deadline")+" · "+val(t,"hours")+" giờ\n"+val(t,"description");
            }
            var text=new JTextArea(body,7,38);
            text.setWrapStyleWord(true);text.setLineWrap(true);text.setEditable(false);
            JOptionPane.showMessageDialog(this,new JScrollPane(text),n.get("title").getAsString(),JOptionPane.INFORMATION_MESSAGE);
        });
    }
}
