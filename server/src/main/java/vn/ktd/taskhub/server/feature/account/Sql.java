package vn.ktd.taskhub.server.feature.account;

import com.google.gson.*;
import java.sql.*;

public final class Sql {
    private Sql() {}
    static PreparedStatement prepare(Connection db,String sql,Object... args) throws SQLException {
        var statement=db.prepareStatement(sql);
        for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
        return statement;
    }
    public static int update(Connection db,String sql,Object... args) throws SQLException {
        try(var s=prepare(db,sql,args)){return s.executeUpdate();}
    }
    public static JsonArray rows(Connection db,String sql,Object... args) throws SQLException {
        var result=new JsonArray();
        try(var s=prepare(db,sql,args);var r=s.executeQuery()){
            var meta=r.getMetaData();
            while(r.next()){
                var row=new JsonObject();
                for(int i=1;i<=meta.getColumnCount();i++){
                    String key=meta.getColumnLabel(i);
                    Object v=r.getObject(i);
                    if(v instanceof Number n) row.addProperty(key,n);
                    else row.addProperty(key,r.getString(i));
                }
                result.add(row);
            }
        }
        return result;
    }
    static JsonObject one(Connection db,String sql,Object... args) throws SQLException {
        var all=rows(db,sql,args);
        return all.size()==0?null:all.get(0).getAsJsonObject();
    }
    static long number(Connection db,String sql,Object... args) throws SQLException {
        try(var s=prepare(db,sql,args);var r=s.executeQuery()){return r.next()?r.getLong(1):0;}
    }
}
