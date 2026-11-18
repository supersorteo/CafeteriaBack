package Cafeteria.plan;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PlanWebConfig implements WebMvcConfigurer {

    private final PlanGuardInterceptor guard;

    public PlanWebConfig(PlanGuardInterceptor guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/v1/admin/**");
    }
}
