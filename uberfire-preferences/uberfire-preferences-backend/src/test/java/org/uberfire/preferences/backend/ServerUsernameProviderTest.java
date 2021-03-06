/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uberfire.preferences.backend;

import javax.enterprise.context.ContextNotActiveException;

import org.junit.Before;
import org.junit.Test;
import org.uberfire.mocks.SessionInfoMock;
import org.uberfire.rpc.SessionInfo;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ServerUsernameProviderTest {

    private SessionInfo sessionInfo;

    private ServerUsernameProvider serverUsernameProvider;

    @Before
    public void setup() {
        sessionInfo = spy(new SessionInfoMock());
        serverUsernameProvider = new ServerUsernameProvider(sessionInfo);
    }

    @Test
    public void testLoggedUserName() {
        final String username = serverUsernameProvider.get();

        verify(sessionInfo).getIdentity();

        assertEquals(sessionInfo.getIdentity().getIdentifier(),
                     username);
    }

    @Test
    public void testNotLoggedUserName() {
        doThrow(new ContextNotActiveException()).when(sessionInfo).getIdentity();

        final String username = serverUsernameProvider.get();

        verify(sessionInfo).getIdentity();

        assertEquals("not-logged-user",
                     username);
    }
}
