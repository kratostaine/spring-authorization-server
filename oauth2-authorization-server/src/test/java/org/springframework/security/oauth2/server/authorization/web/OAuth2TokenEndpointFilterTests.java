/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.web;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;
import org.springframework.util.StringUtils;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OAuth2TokenEndpointFilter}.
 *
 * @author Madhu Bhat
 * @author Joe Grandja
 */
public class OAuth2TokenEndpointFilterTests {
	private AuthenticationManager authenticationManager;
	private OAuth2AuthorizationService authorizationService;
	private OAuth2TokenEndpointFilter filter;
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter =
			new OAuth2ErrorHttpMessageConverter();
	private final HttpMessageConverter<OAuth2AccessTokenResponse> accessTokenHttpResponseConverter =
			new OAuth2AccessTokenResponseHttpMessageConverter();

	@Before
	public void setUp() {
		this.authenticationManager = mock(AuthenticationManager.class);
		this.authorizationService = mock(OAuth2AuthorizationService.class);
		this.filter = new OAuth2TokenEndpointFilter(this.authenticationManager, this.authorizationService);
	}

	@After
	public void cleanup() {
		SecurityContextHolder.clearContext();
	}

	@Test
	public void constructorWhenAuthenticationManagerNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new OAuth2TokenEndpointFilter(null, this.authorizationService))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authenticationManager cannot be null");
	}

	@Test
	public void constructorWhenAuthorizationServiceNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new OAuth2TokenEndpointFilter(this.authenticationManager, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationService cannot be null");
	}

	@Test
	public void constructorWhenTokenEndpointUriNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new OAuth2TokenEndpointFilter(this.authenticationManager, this.authorizationService, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("tokenEndpointUri cannot be empty");
	}

	@Test
	public void doFilterWhenNotTokenRequestThenNotProcessed() throws Exception {
		String requestUri = "/path";
		MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
		request.setServletPath(requestUri);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		this.filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
	}

	@Test
	public void doFilterWhenTokenRequestGetThenNotProcessed() throws Exception {
		String requestUri = OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI;
		MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
		request.setServletPath(requestUri);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		this.filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
	}

	@Test
	public void doFilterWhenTokenRequestMissingGrantTypeThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.removeParameter(OAuth2ParameterNames.GRANT_TYPE);

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.GRANT_TYPE, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenTokenRequestMultipleGrantTypeThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.addParameter(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.AUTHORIZATION_CODE.getValue());

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.GRANT_TYPE, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenTokenRequestInvalidGrantTypeThenUnsupportedGrantTypeError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.setParameter(OAuth2ParameterNames.GRANT_TYPE, "invalid-grant-type");

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.GRANT_TYPE, OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE, request);
	}

	@Test
	public void doFilterWhenTokenRequestMultipleClientIdThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.addParameter(OAuth2ParameterNames.CLIENT_ID, "client-1");
		request.addParameter(OAuth2ParameterNames.CLIENT_ID, "client-2");

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.CLIENT_ID, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenTokenRequestMissingCodeThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.removeParameter(OAuth2ParameterNames.CODE);

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.CODE, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenTokenRequestMultipleCodeThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.addParameter(OAuth2ParameterNames.CODE, "code-2");

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.CODE, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenTokenRequestMultipleRedirectUriThenInvalidRequestError() throws Exception {
		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(
				TestRegisteredClients.registeredClient().build());
		request.addParameter(OAuth2ParameterNames.REDIRECT_URI, "https://example2.com");

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.REDIRECT_URI, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenAuthorizationCodeTokenRequestValidThenAccessTokenResponse() throws Exception {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		Authentication clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "token",
				Instant.now(), Instant.now().plus(Duration.ofHours(1)),
				new HashSet<>(Arrays.asList("scope1", "scope2")));
		OAuth2AccessTokenAuthenticationToken accessTokenAuthentication =
				new OAuth2AccessTokenAuthenticationToken(
						registeredClient, clientPrincipal, accessToken);

		when(this.authenticationManager.authenticate(any())).thenReturn(accessTokenAuthentication);

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(clientPrincipal);
		SecurityContextHolder.setContext(securityContext);

		MockHttpServletRequest request = createAuthorizationCodeTokenRequest(registeredClient);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		this.filter.doFilter(request, response, filterChain);

		verifyNoInteractions(filterChain);

		ArgumentCaptor<OAuth2AuthorizationCodeAuthenticationToken> authorizationCodeAuthenticationCaptor =
				ArgumentCaptor.forClass(OAuth2AuthorizationCodeAuthenticationToken.class);
		verify(this.authenticationManager).authenticate(authorizationCodeAuthenticationCaptor.capture());

		OAuth2AuthorizationCodeAuthenticationToken authorizationCodeAuthentication =
				authorizationCodeAuthenticationCaptor.getValue();
		assertThat(authorizationCodeAuthentication.getCode()).isEqualTo(
				request.getParameter(OAuth2ParameterNames.CODE));
		assertThat(authorizationCodeAuthentication.getPrincipal()).isEqualTo(clientPrincipal);
		assertThat(authorizationCodeAuthentication.getRedirectUri()).isEqualTo(
				request.getParameter(OAuth2ParameterNames.REDIRECT_URI));

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		OAuth2AccessTokenResponse accessTokenResponse = readAccessTokenResponse(response);

		OAuth2AccessToken accessTokenResult = accessTokenResponse.getAccessToken();
		assertThat(accessTokenResult.getTokenType()).isEqualTo(accessToken.getTokenType());
		assertThat(accessTokenResult.getTokenValue()).isEqualTo(accessToken.getTokenValue());
		assertThat(accessTokenResult.getIssuedAt()).isBetween(
				accessToken.getIssuedAt().minusSeconds(1), accessToken.getIssuedAt().plusSeconds(1));
		assertThat(accessTokenResult.getExpiresAt()).isBetween(
				accessToken.getExpiresAt().minusSeconds(1), accessToken.getExpiresAt().plusSeconds(1));
		assertThat(accessTokenResult.getScopes()).isEqualTo(accessToken.getScopes());
	}

	@Test
	public void doFilterWhenTokenRequestMultipleScopeThenInvalidRequestError() throws Exception {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2().build();
		Authentication clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(clientPrincipal);
		SecurityContextHolder.setContext(securityContext);

		MockHttpServletRequest request = createClientCredentialsTokenRequest(registeredClient);
		request.addParameter(OAuth2ParameterNames.SCOPE, "profile");

		doFilterWhenTokenRequestInvalidParameterThenError(
				OAuth2ParameterNames.SCOPE, OAuth2ErrorCodes.INVALID_REQUEST, request);
	}

	@Test
	public void doFilterWhenClientCredentialsTokenRequestValidThenAccessTokenResponse() throws Exception {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2().build();
		Authentication clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "token",
				Instant.now(), Instant.now().plus(Duration.ofHours(1)),
				new HashSet<>(Arrays.asList("scope1", "scope2")));
		OAuth2AccessTokenAuthenticationToken accessTokenAuthentication =
				new OAuth2AccessTokenAuthenticationToken(
						registeredClient, clientPrincipal, accessToken);

		when(this.authenticationManager.authenticate(any())).thenReturn(accessTokenAuthentication);

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(clientPrincipal);
		SecurityContextHolder.setContext(securityContext);

		MockHttpServletRequest request = createClientCredentialsTokenRequest(registeredClient);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		this.filter.doFilter(request, response, filterChain);

		verifyNoInteractions(filterChain);

		ArgumentCaptor<OAuth2ClientCredentialsAuthenticationToken> clientCredentialsAuthenticationCaptor =
				ArgumentCaptor.forClass(OAuth2ClientCredentialsAuthenticationToken.class);
		verify(this.authenticationManager).authenticate(clientCredentialsAuthenticationCaptor.capture());

		OAuth2ClientCredentialsAuthenticationToken clientCredentialsAuthentication =
				clientCredentialsAuthenticationCaptor.getValue();
		assertThat(clientCredentialsAuthentication.getPrincipal()).isEqualTo(clientPrincipal);
		assertThat(clientCredentialsAuthentication.getScopes()).isEqualTo(registeredClient.getScopes());

		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		OAuth2AccessTokenResponse accessTokenResponse = readAccessTokenResponse(response);

		OAuth2AccessToken accessTokenResult = accessTokenResponse.getAccessToken();
		assertThat(accessTokenResult.getTokenType()).isEqualTo(accessToken.getTokenType());
		assertThat(accessTokenResult.getTokenValue()).isEqualTo(accessToken.getTokenValue());
		assertThat(accessTokenResult.getIssuedAt()).isBetween(
				accessToken.getIssuedAt().minusSeconds(1), accessToken.getIssuedAt().plusSeconds(1));
		assertThat(accessTokenResult.getExpiresAt()).isBetween(
				accessToken.getExpiresAt().minusSeconds(1), accessToken.getExpiresAt().plusSeconds(1));
		assertThat(accessTokenResult.getScopes()).isEqualTo(accessToken.getScopes());
	}

	private void doFilterWhenTokenRequestInvalidParameterThenError(String parameterName, String errorCode,
			MockHttpServletRequest request) throws Exception {

		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		this.filter.doFilter(request, response, filterChain);

		verifyNoInteractions(filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		OAuth2Error error = readError(response);
		assertThat(error.getErrorCode()).isEqualTo(errorCode);
		assertThat(error.getDescription()).isEqualTo("OAuth 2.0 Parameter: " + parameterName);
	}

	private OAuth2Error readError(MockHttpServletResponse response) throws Exception {
		MockClientHttpResponse httpResponse = new MockClientHttpResponse(
				response.getContentAsByteArray(), HttpStatus.valueOf(response.getStatus()));
		return this.errorHttpResponseConverter.read(OAuth2Error.class, httpResponse);
	}

	private OAuth2AccessTokenResponse readAccessTokenResponse(MockHttpServletResponse response) throws Exception {
		MockClientHttpResponse httpResponse = new MockClientHttpResponse(
				response.getContentAsByteArray(), HttpStatus.valueOf(response.getStatus()));
		return this.accessTokenHttpResponseConverter.read(OAuth2AccessTokenResponse.class, httpResponse);
	}

	private static MockHttpServletRequest createAuthorizationCodeTokenRequest(RegisteredClient registeredClient) {
		String[] redirectUris = registeredClient.getRedirectUris().toArray(new String[0]);

		String requestUri = OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI;
		MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
		request.setServletPath(requestUri);

		request.addParameter(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
		request.addParameter(OAuth2ParameterNames.CODE, "code");
		request.addParameter(OAuth2ParameterNames.REDIRECT_URI, redirectUris[0]);

		return request;
	}

	private static MockHttpServletRequest createClientCredentialsTokenRequest(RegisteredClient registeredClient) {
		String requestUri = OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI;
		MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
		request.setServletPath(requestUri);

		request.addParameter(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());
		request.addParameter(OAuth2ParameterNames.SCOPE,
				StringUtils.collectionToDelimitedString(registeredClient.getScopes(), " "));

		return request;
	}
}
