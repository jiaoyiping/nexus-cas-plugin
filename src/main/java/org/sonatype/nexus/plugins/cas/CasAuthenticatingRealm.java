package org.sonatype.nexus.plugins.cas;

import java.net.URI;
import java.util.List;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.cas.CasRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.util.CollectionUtils;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.TicketValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.plugins.cas.client.CasRestClient;
import org.sonatype.nexus.plugins.cas.config.CasPluginConfiguration;
import org.sonatype.nexus.plugins.cas.config.model.v1_0_0.Configuration;

/**
 * CAS Authentication Realm using CAS REST API.
 * @author Fabien Crespel <fabien@crespel.net>
 */
@Component( role = Realm.class, hint = CasAuthenticatingRealm.ROLE, description = "CAS Authentication Realm")
public class CasAuthenticatingRealm extends CasRealm implements Initializable {

	public static final String ROLE = "CasAuthenticatingRealm";
	private static final Logger log = LoggerFactory.getLogger(CasAuthenticatingRealm.class);
	
	@Requirement
	private CasPluginConfiguration casPluginConfiguration;
	
	@Requirement
	private CasRestClient casRestClient;
	
	private boolean isConfigured = false;
	
	public CasAuthenticatingRealm() {
		super();
		setAuthenticationTokenClass(UsernamePasswordToken.class);
	}

	@Override
	public void initialize() throws InitializationException {
		configure(casPluginConfiguration.getConfiguration());
	}
	
	protected void configure(Configuration config) {
		if (config != null) {
			setCasServerUrlPrefix(config.getCasServerUrl());
			setCasService(config.getCasService());
			setValidationProtocol(config.getValidationProtocol());
			setRoleAttributeNames(config.getRoleAttributeNames());
			casRestClient.setCasRestTicketUrl(config.getCasRestTicketUrl());
			casRestClient.setTicketValidator(ensureTicketValidator());
			log.info("CAS plugin configured for use with server " + config.getCasServerUrl());
			isConfigured = true;
		}
	}
	
	@Override
	protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
		if (!isConfigured || casRestClient == null || token == null) {
			return null;
		}
		
		UsernamePasswordToken upToken = (UsernamePasswordToken) token;
		URI tgt = null;
		try {
			log.debug("Authenticating user '" + upToken.getUsername() + "' ...");
			tgt = casRestClient.createTicketGrantingTicket(upToken.getUsername(), new String(upToken.getPassword()));
			String st = casRestClient.grantServiceTicket(tgt, getCasService());
			Assertion assertion = casRestClient.validateServiceTicket(st, getCasService());
			
			return createAuthenticationInfo(st, assertion);
			
		} catch (TicketValidationException e) {
			log.error("Error validating remote CAS REST Ticket for user '" + upToken.getUsername() + "'", e);
			throw new AuthenticationException(e);
			
		} catch (Exception e) {
			log.error("Error calling remote CAS REST Ticket API for user '" + upToken.getUsername() + "'", e);
			throw new AuthenticationException(e);
			
		} finally {
			if (tgt != null) {
				try {
					casRestClient.destroyTicketGrantingTicket(tgt);
				} catch (Throwable e) {
					// Ignored
				}
			}
		}
	}
	
	@Override
	protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
		if (!isConfigured || principals.fromRealm(getName()).size() == 0) {
			return null;
		} else {
			return super.doGetAuthorizationInfo(principals);
		}
	}
	
	protected AuthenticationInfo createAuthenticationInfo(String serviceTicket, Assertion assertion) {
		List<Object> principals = CollectionUtils.asList(assertion.getPrincipal().getName(), assertion.getPrincipal().getAttributes());
        PrincipalCollection principalCollection = new SimplePrincipalCollection(principals, getName());
        return new SimpleAuthenticationInfo(principalCollection, serviceTicket);
	}

}
