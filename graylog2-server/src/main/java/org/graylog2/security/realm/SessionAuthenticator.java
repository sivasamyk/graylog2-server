/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.security.realm;

import org.apache.shiro.authc.*;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.graylog2.security.SessionIdToken;
import org.graylog2.plugin.database.users.User;
import org.graylog2.shared.users.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.MultivaluedMap;

public class SessionAuthenticator extends AuthenticatingRealm {
    private static final Logger LOG = LoggerFactory.getLogger(SessionAuthenticator.class);

    private final UserService userService;
    private final LdapUserAuthenticator ldapAuthenticator;

    @Inject
    public SessionAuthenticator(UserService userService, LdapUserAuthenticator ldapAuthenticator) {
        this.userService = userService;
        this.ldapAuthenticator = ldapAuthenticator;
        // this realm either rejects a session, or allows the associated user implicitly
        setAuthenticationTokenClass(SessionIdToken.class);
        setCredentialsMatcher(new AllowAllCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        SessionIdToken sessionIdToken = (SessionIdToken) token;
        final Subject subject = new Subject.Builder().sessionId(sessionIdToken.getSessionId()).buildSubject();
        final Session session = subject.getSession(false);
        if (session == null) {
            LOG.debug("Invalid session {}. Either it has expired or did not exist.", sessionIdToken.getSessionId());
            return null;
        }

        final Object username = subject.getPrincipal();
        final User user = userService.load(String.valueOf(username));
        if (user == null) {
            LOG.debug("No user named {} found for session {}", username, sessionIdToken.getSessionId());
            return null;
        }
        if (user.isExternalUser() && !ldapAuthenticator.isEnabled()) {
            throw new LockedAccountException("LDAP authentication is currently disabled.");
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Found session {} for user name {}", session.getId(), username);
        }

        @SuppressWarnings("unchecked")
        final MultivaluedMap<String, String> requestHeaders = (MultivaluedMap<String, String>) ThreadContext.get(
                "REQUEST_HEADERS");
        // extend session unless the relevant header was passed.
        if (requestHeaders == null || !"true".equalsIgnoreCase(requestHeaders.getFirst("X-Graylog2-No-Session-Extension"))) {
            session.touch();
        } else {
            LOG.debug("Not extending session because the request indicated not to.");
        }
        ThreadContext.bind(subject);

        return new SimpleAccount(user.getName(), null, "session authenticator");
    }
}
