/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.ellipsis.webdav.server.methods;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import nl.ellipsis.webdav.HttpHeaders;
import nl.ellipsis.webdav.server.ITransaction;
import nl.ellipsis.webdav.server.IWebDAVStore;
import nl.ellipsis.webdav.server.StoredObject;
import nl.ellipsis.webdav.server.WebDAVConstants;
import nl.ellipsis.webdav.server.exceptions.AccessDeniedException;
import nl.ellipsis.webdav.server.exceptions.LockFailedException;
import nl.ellipsis.webdav.server.exceptions.WebDAVException;
import nl.ellipsis.webdav.server.locking.ResourceLocks;

public class DoOptions extends DeterminableMethod {

	private static org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DoOptions.class);

	private IWebDAVStore _store;
	private ResourceLocks _resourceLocks;

	public DoOptions(IWebDAVStore store, ResourceLocks resLocks) {
		_store = store;
		_resourceLocks = resLocks;
	}

	public void execute(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException, LockFailedException {
		String path = getRelativePath(req);
		if(LOG.isDebugEnabled()) {
			LOG.debug("-- " + this.getClass().getName()+" "+path);
		}

		String tempLockOwner = "doOptions" + System.currentTimeMillis() + req.toString();
		if (_resourceLocks.lock(transaction, path, tempLockOwner, false, 0, AbstractMethod.getTempTimeout(), TEMPORARY)) {
			StoredObject so = null;
			try {
				resp.addHeader(HttpHeaders.DAV, "1, 2");

				so = _store.getStoredObject(transaction, path);
				String methodsAllowed = determineMethodsAllowed(so);
				resp.addHeader(javax.ws.rs.core.HttpHeaders.ALLOW, methodsAllowed);
				resp.addHeader(HttpHeaders.MS_AUTHOR_VIA, "DAV");
			} catch (AccessDeniedException e) {
				resp.sendError(HttpServletResponse.SC_FORBIDDEN);
			} catch (WebDAVException e) {
				LOG.error("Sending internal error!", e);
				resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			} finally {
				_resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
			}
		} else {
			LOG.error("Sending internal error - Failed to lock");
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
}
