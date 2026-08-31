package cn.ncut.lab.web;

import cn.ncut.lab.service.StoreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final StoreService store;

    public AuthInterceptor(StoreService store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        if (!ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());
        if (isPublic(path)) return true;

        String token = Sessions.tokenFrom(request);
        Map<String, Object> sess = store.getSession(token);
        if (sess == null) throw new ApiException(401, "未登录");
        request.setAttribute(Sessions.ATTR, sess);
        return true;
    }

    private boolean isPublic(String path) {
        return "/api/auth/login".equals(path)
                || "/api/meta".equals(path)
                || "/api/assistant/status".equals(path);
    }
}
