package com.mobi.web.security.util.impl;

/*-
 * #%L
 * com.mobi.web.security
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 - 2026 iNovex Information Systems, Inc.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import com.mobi.jaas.api.engines.Engine;
import com.mobi.jaas.api.engines.EngineManager;
import com.mobi.jaas.api.engines.UserConfig;
import com.mobi.jaas.api.ontologies.usermanagement.Role;
import com.mobi.jaas.api.ontologies.usermanagement.User;
import com.mobi.jaas.api.principals.UserPrincipal;
import com.mobi.web.security.util.api.SecurityHelper;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.security.auth.Subject;
import javax.ws.rs.container.ContainerRequestContext;

@Component(immediate = true)
public class ProxyHeaderSecurityHelper implements SecurityHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyHeaderSecurityHelper.class);
    private static final String DEFAULT_ROLE = "user";

    // Header order matters: prefer dedicated username claims before fallbacking to email.
    private static final List<String> USERNAME_HEADERS = Arrays.asList(
            "X-Forwarded-Preferred-Username",
            "X-Auth-Request-Preferred-Username",
            "X-Forwarded-User",
            "X-Auth-Request-User",
            "X-Forwarded-Email",
            "X-Auth-Request-Email"
    );

    private static final List<String> EMAIL_HEADERS = Arrays.asList(
            "X-Forwarded-Email",
            "X-Auth-Request-Email"
    );
    private static final String AUTHORIZATION_HEADER = "Authorization";

    @Reference
    EngineManager engineManager;

    @Reference(target = "(engineName=RdfEngine)")
    Engine rdfEngine;

    @Override
    public boolean authenticate(ContainerRequestContext context, Subject subject) {
        String username = getFirstHeaderValue(context, USERNAME_HEADERS);
        String email = getFirstHeaderValue(context, EMAIL_HEADERS);

        SignedJWT jwt = parseBearerJwt(context);
        if (jwt != null) {
            if (StringUtils.isBlank(username)) {
                username = firstNonBlank(
                        claim(jwt, "preferred_username"),
                        claim(jwt, "email"),
                        claim(jwt, "sub")
                );
            }
            if (StringUtils.isBlank(email)) {
                email = claim(jwt, "email");
            }
        }

        if (StringUtils.isBlank(username)) {
            return false;
        }
        username = username.trim();
        if (StringUtils.isNotBlank(email)) {
            email = email.trim();
        }

        ensureUserExists(username, email);
        if (!engineManager.userExists(username)) {
            return false;
        }

        subject.getPrincipals().add(new UserPrincipal(username, this.getClass().getSimpleName()));
        return true;
    }

    @Override
    public boolean isUserInRole(Principal principal, String role) {
        if (principal instanceof UserPrincipal) {
            for (Role roleObj : engineManager.getUserRoles(principal.getName())) {
                if (roleObj.getResource().stringValue().contains(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureUserExists(String username, String email) {
        if (engineManager.userExists(username)) {
            return;
        }

        try {
            UserConfig.Builder builder = new UserConfig.Builder(
                    username,
                    UUID.randomUUID().toString(),
                    Collections.singleton(DEFAULT_ROLE)
            );
            if (StringUtils.isNotBlank(email)) {
                builder.email(email);
            }

            User user = engineManager.createUser(rdfEngine.getEngineName(), builder.build());
            if (user == null) {
                LOG.warn("Could not create user object for proxied user {}", username);
                return;
            }
            engineManager.storeUser(rdfEngine.getEngineName(), user);
            LOG.info("Auto-provisioned proxied user {}", username);
        } catch (IllegalArgumentException ex) {
            // Another request may have created the same user in the meantime.
            if (!engineManager.userExists(username)) {
                LOG.warn("Unable to auto-provision proxied user {}", username, ex);
            }
        } catch (Exception ex) {
            LOG.warn("Unexpected error while auto-provisioning proxied user {}", username, ex);
        }
    }

    private String getFirstHeaderValue(ContainerRequestContext context, List<String> headers) {
        for (String header : headers) {
            String value = context.getHeaderString(header);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private SignedJWT parseBearerJwt(ContainerRequestContext context) {
        String authHeader = context.getHeaderString(AUTHORIZATION_HEADER);
        if (StringUtils.isBlank(authHeader) || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        try {
            return SignedJWT.parse(authHeader.substring("Bearer ".length()).trim());
        } catch (Exception ex) {
            LOG.debug("Unable to parse bearer JWT from Authorization header", ex);
            return null;
        }
    }

    private String claim(SignedJWT jwt, String key) {
        try {
            Object claimObj = jwt.getJWTClaimsSet().getClaim(key);
            return claimObj != null ? claimObj.toString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
