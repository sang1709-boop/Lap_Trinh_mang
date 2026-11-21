package vn.ktd.taskhub.server.feature.group.service;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import vn.ktd.taskhub.server.database.Database;
import vn.ktd.taskhub.server.routing.RequestContext;

class GroupServiceTest {
    @Test void rejectsAnonymousGroupRequestsBeforeDatabaseAccess() {
        Properties config=new Properties();config.setProperty("db.url","jdbc:mysql://invalid");config.setProperty("db.user","none");
        GroupService service=new GroupService(new Database(config,null));
        GroupException error=assertThrows(GroupException.class,()->service.execute("GROUP_LIST",new RequestContext("s","127.0.0.1"),new JsonObject()));
        assertEquals("AUTH_REQUIRED",error.code());
    }

    @Test void unavailableTv3PortHasStableError() {
        GroupException error=assertThrows(GroupException.class,()->TaskHandoverPort.unavailable().handover(null,1,1,1,2,3,"test"));
        assertEquals("DEPENDENCY_UNAVAILABLE",error.code());
    }
}
