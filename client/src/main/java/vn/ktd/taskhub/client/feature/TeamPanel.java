package vn.ktd.taskhub.client.feature;

import com.google.gson.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

public abstract class TeamPanel extends JPanel {
    @FunctionalInterface public interface Caller {
        void send(String action,JsonObject data,Consumer<JsonObject> success);
    }
    protected final Caller caller;
    protected final long group;
    protected final boolean leader;
    protected TeamPanel(Caller caller,long group,boolean leader) {
        super(new BorderLayout(8,8)); this.caller=caller;this.group=group;this.leader=leader;
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    }
    protected JsonObject input() {var d=new JsonObject();d.addProperty("groupId",group);return d;}
    protected static JButton button(String title,Runnable run) {
        var b=new JButton(title);b.addActionListener(e->run.run());return b;
    }
    protected static JPanel line(Object... parts) {
        var p=new JPanel(new FlowLayout(FlowLayout.LEADING));
        for(Object part:parts) p.add(part instanceof Component c?c:new JLabel(part.toString()));
        return p;
    }
    protected static JPanel stack(JComponent... parts) {
        var p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        for(var part:parts)p.add(part);return p;
    }
    protected static String val(JsonObject row,String key) {
        var v=row.get(key);
        if(v==null || v.isJsonNull())return "—";
        if(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsString().contains("."))
            return new java.text.DecimalFormat("0.##").format(v.getAsDouble());
        return switch(v.getAsString()) {
            case "not_started" -> "Chưa thực hiện"; case "in_progress" -> "Đang thực hiện";
            case "completed" -> "Hoàn thành"; case "cancelled" -> "Đã hủy";
            case "green","safe" -> "Xanh"; case "red","soon","overdue" -> "Đỏ";
            case "yellow","warning" -> "Vàng"; case "pending" -> "Chờ chấm"; case "approved" -> "Đạt";
            default -> v.getAsString();
        };
    }

    public static final class Grid extends JScrollPane {
        private final JTable table=new JTable();
        private final String[] keys;
        private final DefaultTableModel model;
        private JsonArray rows=new JsonArray();
        public Grid(String[] keys,String... labels) {
            this.keys=keys;
            model=new DefaultTableModel(labels,0) {public boolean isCellEditable(int r,int c){return false;}};
            table.setModel(model);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setFillsViewportHeight(true);setViewportView(table);
            table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){
                public Component getTableCellRendererComponent(JTable t,Object v,boolean selected,boolean focus,int r,int c){
                    var cell=super.getTableCellRendererComponent(t,v,selected,focus,r,c);
                    if(!selected)cell.setBackground(switch(String.valueOf(v)){
                        case "Xanh" -> new Color(210,240,216); case "Vàng" -> new Color(255,238,180);
                        case "Đỏ" -> new Color(255,208,204); default -> t.getBackground();
                    });
                    return cell;
                }
            });
        }
        public void showRows(JsonArray data) {
            rows=data;model.setRowCount(0);
            for(var e:data){var row=e.getAsJsonObject();var values=new String[keys.length];
                for(int i=0;i<keys.length;i++)values[i]=val(row,keys[i]);model.addRow(values);}
        }
        public JsonObject selected() {int r=table.getSelectedRow(); return r<0?null:rows.get(r).getAsJsonObject();}
        public void onOpen(Runnable run){
            table.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)run.run();}});
        }
    }
}
