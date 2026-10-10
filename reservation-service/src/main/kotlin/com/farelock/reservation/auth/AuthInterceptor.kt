package com.farelock.reservation.auth

import com.farelock.reservation.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

const val SESSION_COOKIE = "farelock_session"
const val CURRENT_USER = "currentUser"

fun sessionToken(request: HttpServletRequest): String? =
    request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value?.takeIf { it.isNotBlank() }

/** Runs before protected controllers: resolves the session cookie to a user or rejects with 401. */
@Component
class AuthInterceptor(private val auth: AuthService) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // Suspend handlers complete via an async re-dispatch that runs interceptors again; the user is already known.
        if (request.getAttribute(CURRENT_USER) != null) return true
        val user = sessionToken(request)?.let { auth.userForSession(it) }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please log in")
        request.setAttribute(CURRENT_USER, user)
        return true
    }
}

@Configuration
class WebConfig(private val authInterceptor: AuthInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/me", "/api/reservations", "/api/reservations/**")
    }
}
