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
package org.graylog2.restclient.models;

import org.graylog2.rest.models.roles.responses.RoleMembershipResponse;
import org.graylog2.rest.models.roles.responses.RoleResponse;
import org.graylog2.rest.models.roles.responses.RolesResponse;
import org.graylog2.restclient.lib.APIException;
import org.graylog2.restclient.lib.ApiClient;
import org.graylog2.restroutes.generated.routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

public class RolesService {
    private static final Logger log = LoggerFactory.getLogger(RolesService.class);

    private final ApiClient api;

    @Inject
    public RolesService(ApiClient api) {
        this.api = api;
    }

    public Set<RoleResponse> loadAll() {
        try {
            final RolesResponse rolesResponse = api.path(routes.RolesResource().listAll(),
                                                         RolesResponse.class).execute();
            return rolesResponse.roles();
        } catch (APIException | IOException e) {
            log.error("Unable to load roles list", e);
        }
        return Collections.emptySet();
    }

    @Nullable
    public RoleResponse load(String roleName) {
        try {
            return api.path(routes.RolesResource().read(roleName), RoleResponse.class).execute();
        } catch (APIException | IOException e) {
            log.error("Unable to read role " + roleName, e);
        }
        return null;
    }

    /**
     * Creates a new role.
     * @param newRole new role to create
     * @return created role
     * @throws APIException thrown for a bad request, which likely is "Role already exists"
     */
    @Nullable
    public RoleResponse create(RoleResponse newRole) throws APIException {
        try {
            return api.path(routes.RolesResource().create(), RoleResponse.class).body(newRole).execute();
        } catch (APIException e) {
            log.error("Unable to create role " + newRole.name(), e);
            if (e.getHttpCode() == 400) {
                throw e;
            }
            return null;
        } catch (IOException e) {
            log.error("Unable to create role " + newRole.name(), e);
            return null;
        }
    }

    @Nullable
    public RoleMembershipResponse getMembers(String roleName) {
        try {
            return api.path(routes.RolesResource().getMembers(roleName), RoleMembershipResponse.class).execute();
        } catch (APIException | IOException e) {
            log.error("Unable to retrieve membership set for role " + roleName, e);
        }
        return null;
    }

    public boolean addMembership(String roleName, String userName) {
        try {
            api.path(routes.RolesResource().addMember(roleName, userName)).execute();
            return true;
        } catch (APIException | IOException e) {
            log.error("Unable to add role {} to user {}: {}", roleName, userName, e);
            return false;
        }
    }

    public boolean removeMembership(String roleName, String userName) {
        try {
            api.path(routes.RolesResource().removeMember(roleName, userName)).execute();
            return true;
        } catch (APIException | IOException e) {
            log.error("Unable to remove role {} from user {}: {}", roleName, userName, e);
            return false;
        }
    }

    @Nullable
    public RoleResponse updateRole(String oldRoleName, RoleResponse role) {
        try {
            final RoleResponse response = api.path(routes.RolesResource().update(oldRoleName), RoleResponse.class).body(
                    role).execute();
            return response;
        } catch (APIException | IOException e) {
            log.error("Unable to update role " + oldRoleName, e);
        }
        return null;
    }

    public boolean deleteRole(String roleName) {
        try {
            api.path(routes.RolesResource().delete(roleName)).execute();
            return true;
        } catch (APIException | IOException e) {
            log.error("Unable to delete role " + roleName, e);
        }
        return false;
    }
}
