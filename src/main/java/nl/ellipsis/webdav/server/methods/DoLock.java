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
import java.util.HashMap;
import java.util.Hashtable;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.http.HttpStatus;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import nl.ellipsis.webdav.HttpHeaders;
import nl.ellipsis.webdav.server.ITransaction;
import nl.ellipsis.webdav.server.IWebDAVStore;
import nl.ellipsis.webdav.server.StoredObject;
import nl.ellipsis.webdav.server.WebDAVConstants;
import nl.ellipsis.webdav.server.exceptions.LockFailedException;
import nl.ellipsis.webdav.server.exceptions.WebDAVException;
import nl.ellipsis.webdav.server.locking.IResourceLocks;
import nl.ellipsis.webdav.server.locking.LockedObject;
import nl.ellipsis.webdav.server.util.URLUtil;
import nl.ellipsis.webdav.server.util.XMLWriter;

public class DoLock extends AbstractMethod {

	private static org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DoLock.class);

	private IWebDAVStore _store;
	private IResourceLocks _resourceLocks;
	private boolean _readOnly;

	private boolean _macLockRequest = false;

	private boolean _exclusive = false;
	private String _type = null;
	private String _lockOwner = null;

	private String _path = null;
	private String _parentPath = null;

	private String _userAgent = null;

	public DoLock(IWebDAVStore store, IResourceLocks resourceLocks, boolean readOnly) {
		_store = store;
		_resourceLocks = resourceLocks;
		_readOnly = readOnly;
	}

	public void execute(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException, LockFailedException {
		_path = getRelativePath(req);
		if(LOG.isDebugEnabled()) {
			LOG.debug("-- " + this.getClass().getName()+" "+_path);
		}

		if (_readOnly) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		} else {
			_parentPath = URLUtil.getParentPath(_path);

			// Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

			if (!checkLocks(transaction, req, resp, _resourceLocks, _path)) {
				resp.setStatus(HttpStatus.LOCKED.value());
				return; // resource is locked
			}

			if (!checkLocks(transaction, req, resp, _resourceLocks, _parentPath)) {
				resp.setStatus(HttpStatus.LOCKED.value());
				return; // parent is locked
			}

			// Mac OS Finder (whether 10.4.x or 10.5) can't store files
			// because executing a LOCK without lock information causes a
			// SC_BAD_REQUEST
			_userAgent = req.getHeader(javax.ws.rs.core.HttpHeaders.USER_AGENT);
			if (_userAgent != null && _userAgent.indexOf("Darwin") != -1) {
				_macLockRequest = true;

				String timeString = new Long(System.currentTimeMillis()).toString();
				_lockOwner = _userAgent.concat(timeString);
			}

			String tempLockOwner = "doLock" + System.currentTimeMillis() + req.toString();
			if (_resourceLocks.lock(transaction, _path, tempLockOwner, false, 0, AbstractMethod.getTempTimeout(), TEMPORARY)) {
				try {
					if (req.getHeader(HttpHeaders.IF) != null) {
						doRefreshLock(transaction, req, resp);
					} else {
						doLock(transaction, req, resp);
					}
				} catch (LockFailedException e) {
					resp.sendError(HttpStatus.LOCKED.value());
					LOG.error("Lockfailed exception", e);
				} finally {
					_resourceLocks.unlockTemporaryLockedObjects(transaction, _path, tempLockOwner);
				}
			}
		}
	}

	private void doLock(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException, LockFailedException {

		StoredObject so = _store.getStoredObject(transaction, _path);

		if (so != null) {
			doLocking(transaction, req, resp);
		} else {
			// resource doesn't exist, null-resource lock
			doNullResourceLock(transaction, req, resp);
		}

		so = null;
		_exclusive = false;
		_type = null;
		_lockOwner = null;

	}

	private void doLocking(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		// Tests if LockObject on requested path exists, and if so, tests
		// exclusivity
		LockedObject lo = _resourceLocks.getLockedObjectByPath(transaction, _path);
		if (lo != null) {
			if (lo.isExclusive()) {
				sendLockFailError(transaction, req, resp);
				return;
			}
		}
		try {
			// Thats the locking itself
			executeLock(transaction, req, resp);
		} catch (ServletException e) {
			LOG.error("Sending internal error!", e);
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		} catch (LockFailedException e) {
			sendLockFailError(transaction, req, resp);
		} finally {
			lo = null;
		}

	}

	private void doNullResourceLock(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		StoredObject parentSo, nullSo = null;

		try {
			parentSo = _store.getStoredObject(transaction, _parentPath);
			if (_parentPath != null && parentSo == null) {
				_store.createFolder(transaction, _parentPath);
			} else if (_parentPath != null && parentSo != null && parentSo.isResource()) {
				resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
				return;
			}

			nullSo = _store.getStoredObject(transaction, _path);
			if (nullSo == null) {
				// resource doesn't exist
				_store.createResource(transaction, _path);

				// Transmit expects 204 response-code, not 201
				if (_userAgent != null && _userAgent.indexOf("Transmit") != -1) {
					LOG.debug("DoLock.execute() : do workaround for user agent '" + _userAgent + "'");
					resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
				} else {
					resp.setStatus(HttpServletResponse.SC_CREATED);
				}

			} else {
				// resource already exists, could not execute null-resource lock
				sendLockFailError(transaction, req, resp);
				return;
			}
			nullSo = _store.getStoredObject(transaction, _path);
			// define the newly created resource as null-resource
			nullSo.setNullResource(true);

			// Thats the locking itself
			executeLock(transaction, req, resp);

		} catch (LockFailedException e) {
			sendLockFailError(transaction, req, resp);
		} catch (WebDAVException e) {
			LOG.error("Sending internal error!", e);
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		} catch (ServletException e) {
			LOG.error("Sending internal error!", e);
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		} finally {
			parentSo = null;
			nullSo = null;
		}
	}

	private void doRefreshLock(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException, LockFailedException {

		String[] lockTokens = getLockIdFromIfHeader(req);
		String lockToken = null;
		if (lockTokens != null)
			lockToken = lockTokens[0];

		if (lockToken != null) {
			// Getting LockObject of specified lockToken in If header
			LockedObject refreshLo = _resourceLocks.getLockedObjectByID(transaction, lockToken);
			if (refreshLo != null) {
				int timeout = getTimeout(transaction, req);

				refreshLo.refreshTimeout(timeout);
				// sending success response
				generateXMLReport(transaction, resp, refreshLo);

				refreshLo = null;
			} else {
				// no LockObject to given lockToken
				resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
			}

		} else {
			resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
		}
	}

	// ------------------------------------------------- helper methods

	/**
	 * Executes the LOCK
	 */
	private void executeLock(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws LockFailedException, IOException, ServletException {

		// Mac OS lock request workaround
		if (_macLockRequest) {
			LOG.debug("DoLock.execute() : do workaround for user agent '" + _userAgent + "'");

			doMacLockRequestWorkaround(transaction, req, resp);
		} else {
			// Getting LockInformation from request
			if (getLockInformation(transaction, req, resp)) {
				int depth = getDepth(req);
				int lockDuration = getTimeout(transaction, req);

				boolean lockSuccess = false;
				if (_exclusive) {
					lockSuccess = _resourceLocks.exclusiveLock(transaction, _path, _lockOwner, depth, lockDuration);
				} else {
					lockSuccess = _resourceLocks.sharedLock(transaction, _path, _lockOwner, depth, lockDuration);
				}

				if (lockSuccess) {
					// Locks successfully placed - return information about
					LockedObject lo = _resourceLocks.getLockedObjectByPath(transaction, _path);
					if (lo != null) {
						generateXMLReport(transaction, resp, lo);
					} else {
						LOG.error("Sending internal error - Failed to lock");
						resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
					}
				} else {
					sendLockFailError(transaction, req, resp);

					throw new LockFailedException();
				}
			} else {
				// information for LOCK could not be read successfully
				resp.setContentType("text/xml; charset=UTF-8");
				resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}

	/**
	 * Tries to get the LockInformation from LOCK request
	 */
	private boolean getLockInformation(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		Node lockInfoNode = null;
		try {
			Document document = getDocument(req);
			// Get the root element of the document
			Element rootElement = document.getDocumentElement();

			lockInfoNode = rootElement;

			if (lockInfoNode != null) {
				NodeList childList = lockInfoNode.getChildNodes();
				Node lockScopeNode = null;
				Node lockTypeNode = null;
				Node lockOwnerNode = null;

				Node currentNode = null;
				String nodeName = null;

				for (int i = 0; i < childList.getLength(); i++) {
					currentNode = childList.item(i);

					if (currentNode.getNodeType() == Node.ELEMENT_NODE || currentNode.getNodeType() == Node.TEXT_NODE) {

						nodeName = currentNode.getNodeName();

						if (nodeName.endsWith("locktype")) {
							lockTypeNode = currentNode;
						}
						if (nodeName.endsWith("lockscope")) {
							lockScopeNode = currentNode;
						}
						if (nodeName.endsWith("owner")) {
							lockOwnerNode = currentNode;
						}
					} else {
						return false;
					}
				}

				if (lockScopeNode != null) {
					String scope = null;
					childList = lockScopeNode.getChildNodes();
					for (int i = 0; i < childList.getLength(); i++) {
						currentNode = childList.item(i);

						if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
							scope = currentNode.getNodeName();

							if (scope.endsWith("exclusive")) {
								_exclusive = true;
							} else if (scope.equals("shared")) {
								_exclusive = false;
							}
						}
					}
					if (scope == null) {
						return false;
					}

				} else {
					return false;
				}

				if (lockTypeNode != null) {
					childList = lockTypeNode.getChildNodes();
					for (int i = 0; i < childList.getLength(); i++) {
						currentNode = childList.item(i);

						if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
							_type = currentNode.getNodeName();

							if (_type.endsWith("write")) {
								_type = "write";
							} else if (_type.equals("read")) {
								_type = "read";
							}
						}
					}
					if (_type == null) {
						return false;
					}
				} else {
					return false;
				}

				if (lockOwnerNode != null) {
					childList = lockOwnerNode.getChildNodes();
					for (int i = 0; i < childList.getLength(); i++) {
						currentNode = childList.item(i);				
						if (currentNode.getNodeType() == Node.ELEMENT_NODE || currentNode.getNodeType() == Node.TEXT_NODE) {
							_lockOwner = currentNode.hasChildNodes() ? currentNode.getFirstChild().getNodeValue() : currentNode.getNodeValue();
						}
					}
				}
				if (_lockOwner == null) {
					return false;
				}
			} else {
				return false;
			}

		} catch (DOMException e) {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			LOG.error("DOM exception", e);
			return false;
		} catch (SAXException | ParserConfigurationException e) {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			LOG.error("SAX exception", e);
			return false;
		}

		return true;
	}

	/**
	 * Ties to read the timeout from request
	 */
	private int getTimeout(ITransaction transaction, HttpServletRequest req) {

		int lockDuration = AbstractMethod.getDefaultTimeout();
		String lockDurationStr = req.getHeader(HttpHeaders.TIMEOUT);

		if (lockDurationStr == null) {
			lockDuration = AbstractMethod.getDefaultTimeout();
		} else {
			int commaPos = lockDurationStr.indexOf(',');
			// if multiple timeouts, just use the first one
			if (commaPos != -1) {
				lockDurationStr = lockDurationStr.substring(0, commaPos);
			}
			if (lockDurationStr.startsWith("Second-")) {
				lockDuration = new Integer(lockDurationStr.substring(7)).intValue();
			} else {
				if (lockDurationStr.equalsIgnoreCase("infinity")) {
					lockDuration = AbstractMethod.getMaxTimeout();
				} else {
					try {
						lockDuration = new Integer(lockDurationStr).intValue();
					} catch (NumberFormatException e) {
						lockDuration = AbstractMethod.getMaxTimeout();
					}
				}
			}
			if (lockDuration <= 0) {
				lockDuration = AbstractMethod.getDefaultTimeout();
			}
			if (lockDuration > AbstractMethod.getMaxTimeout()) {
				lockDuration = AbstractMethod.getMaxTimeout();
			}
		}
		return lockDuration;
	}

	/**
	 * Generates the response XML with all lock information
	 */
	private void generateXMLReport(ITransaction transaction, HttpServletResponse resp, LockedObject lo)
			throws IOException {

		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType("text/xml; charset=UTF-8");

		XMLWriter generatedXML = new XMLWriter(resp.getWriter());
		generatedXML.writeXMLHeader();
		generatedXML.writeElement(NS_DAV_PREFIX,NS_DAV_FULLNAME,WebDAVConstants.XMLTag.PROP, XMLWriter.OPENING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKDISCOVERY, XMLWriter.OPENING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.ACTIVELOCK, XMLWriter.OPENING);

		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKTYPE, XMLWriter.OPENING);
		generatedXML.writeProperty(NS_DAV_PREFIX,_type);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKTYPE, XMLWriter.CLOSING);

		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKSCOPE, XMLWriter.OPENING);
		if (_exclusive) {
			generatedXML.writeProperty(NS_DAV_PREFIX,"exclusive");
		} else {
			generatedXML.writeProperty(NS_DAV_PREFIX,"shared");
		}
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKSCOPE, XMLWriter.CLOSING);

		int depth = lo.getLockDepth();

		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.DEPTH, XMLWriter.OPENING);
		if (depth == DEPTH_INFINITY) {
			generatedXML.writeText(S_DEPTH_INFINITY);
		} else {
			generatedXML.writeText(String.valueOf(depth));
		}
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.DEPTH, XMLWriter.CLOSING);

		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.OWNER, XMLWriter.OPENING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.HREF, XMLWriter.OPENING);
		generatedXML.writeText(_lockOwner);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.HREF, XMLWriter.CLOSING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.OWNER, XMLWriter.CLOSING);

		long timeout = lo.getTimeoutMillis();
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.TIMEOUT, XMLWriter.OPENING);
		generatedXML.writeText("Second-" + timeout / 1000);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.TIMEOUT, XMLWriter.CLOSING);

		String lockToken = lo.getID();
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKTOKEN, XMLWriter.OPENING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.HREF, XMLWriter.OPENING);
		generatedXML.writeText("opaquelocktoken:" + lockToken);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.HREF, XMLWriter.CLOSING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKTOKEN, XMLWriter.CLOSING);

		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.ACTIVELOCK, XMLWriter.CLOSING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.LOCKDISCOVERY, XMLWriter.CLOSING);
		generatedXML.writeElement(NS_DAV_PREFIX,WebDAVConstants.XMLTag.PROP, XMLWriter.CLOSING);

		resp.addHeader(HttpHeaders.LOCK_TOKEN, "<opaquelocktoken:" + lockToken + ">");

		generatedXML.sendData("doLock.response "+lo.getPath()+"\n");
	}

	/**
	 * Executes the lock for a Mac OS Finder client
	 */
	private void doMacLockRequestWorkaround(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws LockFailedException, IOException {
		LockedObject lo;
		int depth = getDepth(req);
		int lockDuration = getTimeout(transaction, req);
		if (lockDuration < 0 || lockDuration > AbstractMethod.getMaxTimeout())
			lockDuration = AbstractMethod.getDefaultTimeout();

		boolean lockSuccess = false;
		/*
		  (FUN-15544) Do not use exclusive locks for MacOS finder requests
		  macos sends the first LOCK request -> resource temporary locked with an exclusive lock -> return 200
		  macos send second LOCK request -> code checks if the object is already locked -> and then checks if the lock is exclusive
		  if the lock is exclusive it sends an error code (423 LOCKED) in response. Check Line number 155
		  Therefore do not use exclusive locks for MacOS finder requests
		 */
		lockSuccess = _resourceLocks.lock(transaction, _path, _lockOwner, false, depth, lockDuration, TEMPORARY);

		if (lockSuccess) {
			// Locks successfully placed - return information about
			lo = _resourceLocks.getTempLockedObjectByPath(transaction, _path);
			if (lo != null) {
				generateXMLReport(transaction, resp, lo);
			} else {
				LOG.error("Sending internal error - Failed to lock");
				resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
		} else {
			// Locking was not successful
			sendLockFailError(transaction, req, resp);
		}
	}

	/**
	 * Sends an error report to the client
	 */
	private void sendLockFailError(ITransaction transaction, HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();
		errorList.put(_path, HttpStatus.LOCKED.value());
		sendReport(req, resp, errorList);
	}

}
