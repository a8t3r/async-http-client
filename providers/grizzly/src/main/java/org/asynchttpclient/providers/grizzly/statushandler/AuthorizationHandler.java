/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.providers.grizzly.statushandler;

import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.ConnectionManager;
import org.asynchttpclient.providers.grizzly.HttpTransactionContext;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpStatus;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;

import static org.asynchttpclient.providers.grizzly.statushandler.StatusHandler.InvocationStatus.STOP;

public final class AuthorizationHandler implements StatusHandler {

    public static final AuthorizationHandler INSTANCE =
            new AuthorizationHandler();

    // ---------------------------------------------- Methods from StatusHandler


    public boolean handlesStatus(int statusCode) {
        return (HttpStatus.UNAUTHORIZED_401.statusMatches(statusCode));
    }

    @SuppressWarnings({"unchecked"})
    public boolean handleStatus(final HttpResponsePacket responsePacket,
                                final HttpTransactionContext httpTransactionContext,
                                final FilterChainContext ctx) {

        final String auth = responsePacket.getHeader(Header.WWWAuthenticate);
        if (auth == null) {
            throw new IllegalStateException("401 response received, but no WWW-Authenticate header was present");
        }

        Realm realm = httpTransactionContext.getRequest().getRealm();
        if (realm == null) {
            realm = httpTransactionContext.getProvider().getClientConfig().getRealm();
        }
        if (realm == null) {
            httpTransactionContext.setInvocationStatus(STOP);
            return true;
        }

        responsePacket.setSkipRemainder(true); // ignore the remainder of the response

        final Request req = httpTransactionContext.getRequest();
        realm = new Realm.RealmBuilder().clone(realm)
                        .setScheme(realm.getAuthScheme())
                        .setUri(req.getURI().getPath())
                        .setMethodName(req.getMethod())
                        .setUsePreemptiveAuth(true)
                        .parseWWWAuthenticateHeader(auth)
                        .build();
        if (auth.toLowerCase().startsWith("basic")) {
            req.getHeaders().remove(Header.Authorization.toString());
            try {
                req.getHeaders().add(Header.Authorization.toString(),
                                     AuthenticatorUtils.computeBasicAuthentication(
                                             realm));
            } catch (UnsupportedEncodingException ignored) {
            }
        } else if (auth.toLowerCase().startsWith("digest")) {
            req.getHeaders().remove(Header.Authorization.toString());
            try {
                req.getHeaders().add(Header.Authorization.toString(),
                                     AuthenticatorUtils.computeDigestAuthentication(realm));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Digest authentication not supported", e);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("Unsupported encoding.", e);
            }
        } else {
            throw new IllegalStateException("Unsupported authorization method: " + auth);
        }

        final ConnectionManager m = httpTransactionContext.getProvider().getConnectionManager();
        try {
            final Connection c = m.obtainConnection(req,
                                                    httpTransactionContext.getFuture());
            final HttpTransactionContext newContext =
                    httpTransactionContext.copy();
            httpTransactionContext.setFuture(null);
            HttpTransactionContext.set(c, newContext);
            newContext.setInvocationStatus(STOP);
            try {
                httpTransactionContext.getProvider().execute(c,
                                                        req,
                                                        httpTransactionContext.getHandler(),
                                                        httpTransactionContext.getFuture());
                return false;
            } catch (IOException ioe) {
                newContext.abort(ioe);
                return false;
            }
        } catch (Exception e) {
            httpTransactionContext.abort(e);
        }
        httpTransactionContext.setInvocationStatus(STOP);
        return false;
    }

} // END AuthorizationHandler
