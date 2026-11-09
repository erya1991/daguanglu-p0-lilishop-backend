package cn.lili.modules.community.p0.support;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Component
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class Stage1Context {
    private final CurrentSessionAuthorization authorization;
    private final Stage1Access access;
    public Stage1Context(CurrentSessionAuthorization authorization,Stage1Access access){this.authorization=authorization;this.access=access;}
    public record Actor(SessionIdentity identity,AuthUser user,String method,String path) {
        public String id(){return identity.subject();}
        public SessionDomain domain(){return identity.domain();}
        public String store(){return user==null?null:user.getStoreId();}
        public String clerk(){return user==null?null:user.getClerkId();}
        @Override public String toString(){return "Stage1Actor[REDACTED]";}
    }
    public Actor current(){
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||!(authentication.getDetails() instanceof SessionHttpFilter.Principal principal)) throw new Stage1Exception(401,"AUTH_REQUIRED");
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))throw new Stage1Exception(401,"AUTH_REQUIRED");
        HttpServletRequest request=attributes.getRequest();
        var actor=new Actor(principal.session().identity(),principal.user(),request.getMethod(),request.getRequestURI());
        recheck(actor);return actor;
    }
    public void recheck(Actor actor){
        AuthUser fresh=authorization.current(actor.identity());
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getDetails() instanceof SessionHttpFilter.Principal principal)||!principal.session().identity().equals(actor.identity()))throw new Stage1Exception(401,"AUTH_REQUIRED");
        access.checkIssuedAt(actor.identity(),principal.session().issuedAt());
        if(actor.domain()==SessionDomain.STORE&&(fresh==null||!java.util.Objects.equals(actor.store(),fresh.getStoreId())||!java.util.Objects.equals(actor.clerk(),fresh.getClerkId())))throw new Stage1Exception(403,"FORBIDDEN");
        authorization.checkRoute(fresh,actor.method(),actor.path(),true);
    }
    public Actor require(SessionDomain domain){var actor=current();if(actor.domain()!=domain)throw new Stage1Exception(403,"DOMAIN_MISMATCH");return actor;}
    public Object receipt(String action,Object result){
        var actor=current();
        // A Stage45 query uses the native durable queue receipt without granting native onboarding.
        if(actor.domain()==SessionDomain.MANAGER&&actor.method().equals("POST")&&actor.path().matches("/manager/p0/s45/channels/[1-9][0-9]{0,18}/sync")&&action.startsWith("submerchant.query:"))return Stage1Json.object(result).deepCopy();
        var grants=actor.domain()==SessionDomain.MANAGER?access.managerPermissions(actor.id()):actor.domain()==SessionDomain.STORE?access.storePermissions(actor.user()):java.util.Set.<String>of();
        return Stage1ReceiptPolicy.view(actor.domain(),grants,action,result);
    }
}
