package vn.ktd.taskhub.client.feature;

import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.util.function.IntConsumer;
import vn.ktd.taskhub.client.feature.notification.InboxPanel;
import vn.ktd.taskhub.client.feature.progress.ProgressPanel;
import vn.ktd.taskhub.client.feature.workload.WorkloadPanel;

public final class TeamWindow extends JDialog {
    private record Group(long id,String name,boolean leader){public String toString(){return name;}}
    private final JComboBox<Group> groups=new JComboBox<>();
    private final JTabbedPane tabs=new JTabbedPane();
    private final InboxPanel inbox;
    private final TeamPanel.Caller caller;
    public TeamWindow(JFrame owner,JsonArray list,TeamPanel.Caller caller,IntConsumer badge){
        super(owner,"KTD Task Hub · Công việc nhóm",false);this.caller=caller;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        inbox=new InboxPanel(caller,n->{
            int i=tabs.indexOfComponent(inboxPanel());if(i>=0)tabs.setTitleAt(i,"Hộp thông báo ("+n+")");badge.accept(n);
        });
        for(var e:list){var g=e.getAsJsonObject();groups.addItem(new Group(g.get("group_id").getAsLong(),
                g.get("name").getAsString(),g.get("member_role").getAsString().equals("leader")));}
        if(groups.getItemCount()>0){var bar=new JPanel(new FlowLayout(FlowLayout.LEADING));bar.add(new JLabel("Nhóm"));bar.add(groups);add(bar,BorderLayout.NORTH);}
        add(tabs);selectGroup();groups.addActionListener(e->selectGroup());
        tabs.addChangeListener(e->{if(tabs.getSelectedComponent()==inbox)inbox.refresh();});
        setSize(1000,650);setLocationRelativeTo(owner);
    }
    private InboxPanel inboxPanel(){return inbox;}
    private void selectGroup(){
        tabs.removeAll();
        var g=(Group)groups.getSelectedItem();
        if(g!=null){
            tabs.addTab("Tải và phân công",new WorkloadPanel(caller,g.id(),g.leader()));
            tabs.addTab("Tiến độ và báo cáo",new ProgressPanel(caller,g.id(),g.leader()));
        }
        tabs.addTab("Hộp thông báo",inbox);inbox.refresh();
    }
    public void refreshInbox(){inbox.refresh();}
}
