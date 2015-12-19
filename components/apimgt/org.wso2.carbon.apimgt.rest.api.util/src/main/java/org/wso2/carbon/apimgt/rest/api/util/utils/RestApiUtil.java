/*
 *
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.wso2.carbon.apimgt.rest.api.util.utils;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.message.Message;
import org.wso2.carbon.apimgt.api.APIConsumer;
import org.wso2.carbon.apimgt.api.APIDefinition;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIMgtAuthorizationFailedException;
import org.wso2.carbon.apimgt.api.APIMgtResourceAlreadyExistsException;
import org.wso2.carbon.apimgt.api.APIMgtResourceNotFoundException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.DuplicateAPIException;
import org.wso2.carbon.apimgt.api.model.KeyManager;
import org.wso2.carbon.apimgt.api.model.OAuthAppRequest;
import org.wso2.carbon.apimgt.api.model.OAuthApplicationInfo;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.AMDefaultKeyManagerImpl;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.definitions.APIDefinitionFromSwagger20;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.rest.api.util.RestApiConstants;
import org.wso2.carbon.apimgt.rest.api.util.dto.ErrorDTO;
import org.wso2.carbon.apimgt.rest.api.util.dto.ErrorListItemDTO;
import org.wso2.carbon.apimgt.rest.api.util.exception.*;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.registry.core.exceptions.ResourceNotFoundException;
import org.wso2.carbon.registry.core.secure.AuthorizationFailedException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.validation.ConstraintViolation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestApiUtil {

    private static final Log log = LogFactory.getLog(RestApiUtil.class);
    private static Set<URITemplate> storeResourceMappings;
    private static Set<URITemplate> publisherResourceMappings;
    public static final ThreadLocal userThreadLocal = new ThreadLocal();

    public static void setThreadLocalRequestedTenant(String user) {
        userThreadLocal.set(user);
    }

    public static void unsetThreadLocalRequestedTenant() {
        userThreadLocal.remove();
    }

    public static String getThreadLocalRequestedTenant() {
        return (String)userThreadLocal.get();
    }

    public static APIProvider getLoggedInUserProvider() throws APIManagementException {
        String loggedInUser = CarbonContext.getThreadLocalCarbonContext().getUsername();
        return APIManagerFactory.getInstance().getAPIProvider(loggedInUser);
    }

    public static APIProvider getProvider(String username) throws APIManagementException {
        return APIManagerFactory.getInstance().getAPIProvider(username);
    }

    public static <T> ErrorDTO getConstraintViolationErrorDTO(Set<ConstraintViolation<T>> violations) {
        ErrorDTO errorDTO = new ErrorDTO();
        errorDTO.setMessage("Constraint Violation");
        List<ErrorListItemDTO> errorListItemDTOs = new ArrayList<>();
        for (ConstraintViolation violation : violations) {
            ErrorListItemDTO errorListItemDTO = new ErrorListItemDTO();
            errorListItemDTO.setMessage(violation.getPropertyPath() + ": " + violation.getMessage());
            errorListItemDTOs.add(errorListItemDTO);
        }
        errorDTO.setError(errorListItemDTOs);
        return errorDTO;
    }

    /**
     * Returns a generic errorDTO
     * 
     * @param message specifies the error message
     * @return A generic errorDTO with the specified details
     */
    public static ErrorDTO getErrorDTO(String message, Long code, String description){
        ErrorDTO errorDTO = new ErrorDTO();
        errorDTO.setCode(code);
        errorDTO.setMoreInfo("");
        errorDTO.setMessage(message);
        errorDTO.setDescription(description);
        return errorDTO;
    }

    /**
     * Check whether the specified apiId is of type UUID
     * 
     * @param apiId api identifier
     * @return true if apiId is of type UUID, false otherwise
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static boolean isUUID(String apiId) {
        try {
            UUID.fromString(apiId);
            return true;
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug(apiId + " is not a valid UUID");
            }
            return false;
        }

    }

    /**
     * Url validator, Allow any url with https and http.
     * Allow any url without fully qualified domain
     *
     * @param url Url as string
     * @return boolean type stating validated or not
     */
    public static boolean isURL(String url) {

        Pattern pattern = Pattern.compile("^(http|https)://(.)+", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(url);
        return matcher.matches();

    }

    public static APIConsumer getConsumer(String subscriberName) throws APIManagementException {
        return APIManagerFactory.getInstance().getAPIConsumer(subscriberName);
    }

    /** Returns an APIConsumer which is corresponding to the current logged in user taken from the carbon context
     * 
     * @return an APIConsumer which is corresponding to the current logged in user
     * @throws APIManagementException
     */
    public static APIConsumer getLoggedInUserConsumer() throws APIManagementException {
        String loggedInUser = CarbonContext.getThreadLocalCarbonContext().getUsername();
        return APIManagerFactory.getInstance().getAPIConsumer(loggedInUser);
    }

    public static String getLoggedInUsername() {
        return CarbonContext.getThreadLocalCarbonContext().getUsername();
    }

    public static String getLoggedInUserTenantDomain() {
        return CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
    }

    /**
     * Check if the user's tenant and the API's tenant is equal. If it is not this will throw an 
     * APIMgtAuthorizationFailedException
     * 
     * @param apiIdentifier API Identifier
     * @throws APIMgtAuthorizationFailedException
     */
    public static void validateUserTenantWithAPIIdentifier(APIIdentifier apiIdentifier)
            throws APIMgtAuthorizationFailedException {
        String username = RestApiUtil.getLoggedInUsername();
        String providerName = APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName());
        String providerTenantDomain = MultitenantUtils.getTenantDomain(providerName);
        String loggedInUserTenantDomain = RestApiUtil.getLoggedInUserTenantDomain();
        if (!providerTenantDomain.equals(loggedInUserTenantDomain)) {
            String errorMsg = "User " + username + " is not allowed to access " + apiIdentifier.toString()
                    + " as it belongs to a different tenant : " + providerTenantDomain;
            throw new APIMgtAuthorizationFailedException(errorMsg);
        }
    }

    /**
     * Returns the requested tenant according to the input x-tenant-header
     * 
     * @return requested tenant domain
     */
    public static String getRequestedTenantDomain(String xTenantHeader) {
        if (StringUtils.isEmpty(xTenantHeader)) {
            return getLoggedInUserTenantDomain();
        } else {
            return xTenantHeader;
        }
    }

    /**
     * This method uploads a given file to specified location
     *
     * @param uploadedInputStream input stream of the file
     * @param newFileName         name of the file to be created
     * @param storageLocation     destination of the new file
     * @throws APIManagementException if the file transfer fails
     */
    public static void transferFile(InputStream uploadedInputStream, String newFileName, String storageLocation)
            throws APIManagementException {
        FileOutputStream outFileStream = null;

        try {
            outFileStream = new FileOutputStream(new File(storageLocation, newFileName));
            int read;
            byte[] bytes = new byte[1024];
            while ((read = uploadedInputStream.read(bytes)) != -1) {
                outFileStream.write(bytes, 0, read);
            }
        } catch (IOException e) {
            String errorMessage = "Error in transferring files.";
            log.error(errorMessage, e);
            throw new APIManagementException(errorMessage, e);
        } finally {
            IOUtils.closeQuietly(outFileStream);
        }
    }

    /**
     * Returns a new NotFoundException
     * 
     * @param resource Resource type
     * @param id identifier of the resource
     * @return a new NotFoundException with the specified details as a response DTO
     */
    public static NotFoundException buildNotFoundException(String resource, String id) {
        String description;
        if (!StringUtils.isEmpty(id)) {
            description = "Requested " + resource + " with Id '" + id + "' not found";
        } else {
            description = "Requested " + resource + " not found";
        }
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_NOT_FOUND_MESSAGE_DEFAULT, 404l, description);
        return new NotFoundException(errorDTO);
    }

    /**
     * Returns a new NotFoundException
     *
     * @param description description of the error
     * @return a new NotFoundException with the specified details as a response DTO
     */
    public static NotFoundException buildNotFoundException(String description) {
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_NOT_FOUND_MESSAGE_DEFAULT, 404l, description);
        return new NotFoundException(errorDTO);
    }

    /**
     * Returns a new ForbiddenException
     * 
     * @param resource Resource type
     * @param id identifier of the resource
     * @return a new ForbiddenException with the specified details as a response DTO
     */
    public static ForbiddenException buildForbiddenException(String resource, String id) {
        String description;
        if (!StringUtils.isEmpty(id)) {
            description = "You don't have permission to access the " + resource + " with Id " + id;
        } else {
            description = "You don't have permission to access the " + resource;
        }
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_FORBIDDEN_MESSAGE_DEFAULT, 403l, description);
        return new ForbiddenException(errorDTO);
    }

    /**
     * Returns a new ForbiddenException
     *
     * @param description description of the failure
     * @return a new ForbiddenException with the specified details as a response DTO
     */
    public static ForbiddenException buildForbiddenException(String description) {
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_FORBIDDEN_MESSAGE_DEFAULT, 403l, description);
        return new ForbiddenException(errorDTO);
    }

    /**
     * Returns a new BadRequestException
     * 
     * @param description description of the exception
     * @return a new BadRequestException with the specified details as a response DTO
     */
    public static BadRequestException buildBadRequestException(String description) {
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_BAD_REQUEST_MESSAGE_DEFAULT, 400l, description);
        return new BadRequestException(errorDTO);
    }

    /**
     * Returns a new MethodNotAllowedException
     * 
     * @param method http method
     * @param resource resource which the method is not allowed
     * @return a new MethodNotAllowedException consists of the error message
     */
    public static MethodNotAllowedException buildMethodNotAllowedException(String method, String resource) {
        String description = "Method " + method + " is not supported for " + resource;
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_BAD_REQUEST_MESSAGE_DEFAULT, 405l, description);
        return new MethodNotAllowedException(errorDTO);
    }

    /**
     * Returns a new ConflictException
     * 
     * @param description description of the exception
     * @return a new ConflictException with the specified details as a response DTO
     */
    public static ConflictException buildConflictException(String description) {
        ErrorDTO errorDTO = getErrorDTO(RestApiConstants.STATUS_CONFLCIT_MESSAGE_DEFAULT, 409l, description);
        return new ConflictException(errorDTO);
    }

    /**
     * Check if the specified throwable e is due to an authorization failure
     * @param e throwable to check
     * @return true if the specified throwable e is due to an authorization failure, false otherwise
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public static boolean isDueToAuthorizationFailure(Throwable e) {
        Throwable rootCause = getPossibleErrorCause(e);
        return rootCause instanceof AuthorizationFailedException
                || rootCause instanceof APIMgtAuthorizationFailedException;
    }

    /**
     * Check if the specified throwable e is happened as the required resource cannot be found
     * @param e throwable to check
     * @return true if the specified throwable e is happened as the required resource cannot be found, false otherwise
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public static boolean isDueToResourceNotFound(Throwable e) {
        Throwable rootCause = getPossibleErrorCause(e);
        return rootCause instanceof APIMgtResourceNotFoundException
                || rootCause instanceof ResourceNotFoundException;
    }

    /**
     * Check if the specified throwable e is happened as the updated/new resource conflicting with an already existing
     * resource
     * 
     * @param e throwable to check
     * @return true if the specified throwable e is happened as the updated/new resource conflicting with an already
     *   existing resource, false otherwise
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public static boolean isDueToResourceAlreadyExists(Throwable e) {
        Throwable rootCause = getPossibleErrorCause(e);
        return rootCause instanceof APIMgtResourceAlreadyExistsException || rootCause instanceof DuplicateAPIException;
    }

    /**
     * Check if the message of the root cause message of 'e' matches with the specified message
     * 
     * @param e throwable to check
     * @param message error message
     * @return true if the message of the root cause of 'e' matches with 'message'
     */
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    public static boolean rootCauseMessageMatches (Throwable e, String message) {
        Throwable rootCause = getPossibleErrorCause(e);
        return rootCause.getMessage().matches(".*" + message + ".*");
    }

    /**
     * Attempts to find the actual cause of the throwable 'e'
     * 
     * @param e throwable
     * @return the root cause of 'e' if the root cause exists, otherwise returns 'e' itself
     */
    private static Throwable getPossibleErrorCause (Throwable e) {
        Throwable rootCause = ExceptionUtils.getRootCause(e);
        rootCause = rootCause == null ? e : rootCause;
        return rootCause;
    }

    /**
     * Checks whether the specified tenant domain is available
     * 
     * @param tenantDomain tenant domain
     * @return true if tenant domain available
     * @throws UserStoreException
     */
    public static boolean isTenantAvailable(String tenantDomain) throws UserStoreException {
        int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                .getTenantId(tenantDomain);
        return tenantId != -1;
    }

    /**
     * Check whether the HTTP method is allowed for given resources
     *
     * @param method HTTP method
     * @param resource requested resource
     * @throws MethodNotAllowedException if the method is not supported
     */
    public static void checkAllowedMethodForResource(String method, String resource) throws MethodNotAllowedException {
        if (RestApiConstants.RESOURCE_PATH_TIERS_APPLICATION.equals(resource)
                || RestApiConstants.RESOURCE_PATH_TIERS_RESOURCE.equals(resource)) {
            if (!"GET".equals(method)) {
                throw RestApiUtil.buildMethodNotAllowedException(method, resource);
            }
        }
    }

    /**
     * Returns the next/previous offset/limit parameters properly when current offset, limit and size parameters are specified
     *
     * @param offset current starting index
     * @param limit current max records
     * @param size maximum index possible
     * @return the next/previous offset/limit parameters as a hash-map
     */
    public static Map<String, Integer> getPaginationParams(Integer offset, Integer limit, Integer size) {
        Map<String, Integer> result = new HashMap<>();
        if (offset >= size || offset < 0)
            return result;

        int start = offset;
        int end = offset + limit - 1;

        int nextStart = end + 1;
        if (nextStart < size) {
            result.put(RestApiConstants.PAGINATION_NEXT_OFFSET, nextStart);
            result.put(RestApiConstants.PAGINATION_NEXT_LIMIT, limit);
        }

        int previousEnd = start - 1;
        int previousStart = previousEnd - limit + 1;

        if (previousEnd >= 0) {
            if (previousStart < 0) {
                result.put(RestApiConstants.PAGINATION_PREVIOUS_OFFSET, 0);
                result.put(RestApiConstants.PAGINATION_PREVIOUS_LIMIT, limit);
            } else {
                result.put(RestApiConstants.PAGINATION_PREVIOUS_OFFSET, previousStart);
                result.put(RestApiConstants.PAGINATION_PREVIOUS_LIMIT, limit);
            }
        }
        return result;
    }

    /** Returns the paginated url for APIs API
     *
     * @param offset starting index
     * @param limit max number of objects returned
     * @param query search query value
     * @return constructed paginated url
     */
    public static String getAPIPaginatedURL(Integer offset, Integer limit, String query) {
        String paginatedURL = RestApiConstants.APIS_GET_PAGINATION_URL;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        paginatedURL = paginatedURL.replace(RestApiConstants.QUERY_PARAM, query);
        return paginatedURL;
    }

    /** Returns the paginated url for Applications API
     *
     * @param offset starting index
     * @param limit max number of objects returned
     * @param groupId groupId of the Application
     * @return constructed paginated url
     */
    public static String getApplicationPaginatedURL(Integer offset, Integer limit, String groupId) {
        groupId = groupId == null ? "" : groupId;
        String paginatedURL = RestApiConstants.APPLICATIONS_GET_PAGINATION_URL;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        paginatedURL = paginatedURL.replace(RestApiConstants.GROUPID_PARAM, groupId);
        return paginatedURL;
    }

    /** Returns the paginated url for subscriptions for a particular API identifier
     * 
     * @param offset starting index
     * @param limit max number of objects returned
     * @param apiId API Identifier
     * @param groupId groupId of the Application
     * @return constructed paginated url
     */
    public static String getSubscriptionPaginatedURLForAPIId(Integer offset, Integer limit, String apiId,
            String groupId) {
        groupId = groupId == null ? "" : groupId;
        String paginatedURL = RestApiConstants.SUBSCRIPTIONS_GET_PAGINATION_URL_APIID;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        paginatedURL = paginatedURL.replace(RestApiConstants.APIID_PARAM, apiId);
        paginatedURL = paginatedURL.replace(RestApiConstants.GROUPID_PARAM, groupId);
        return paginatedURL;
    }

    /** Returns the paginated url for subscriptions for a particular application
     * 
     * @param offset starting index
     * @param limit max number of objects returned
     * @param applicationId application id
     * @return constructed paginated url
     */
    public static String getSubscriptionPaginatedURLForApplicationId(Integer offset, Integer limit,
            String applicationId) {
        String paginatedURL = RestApiConstants.SUBSCRIPTIONS_GET_PAGINATION_URL_APPLICATIONID;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        paginatedURL = paginatedURL.replace(RestApiConstants.APPLICATIONID_PARAM, applicationId);
        return paginatedURL;
    }

    /** Returns the paginated url for documentations
     *
     * @param offset starting index
     * @param limit max number of objects returned
     * @return constructed paginated url
     */
    public static String getDocumentationPaginatedURL(Integer offset, Integer limit, String apiId) {
        String paginatedURL = RestApiConstants.DOCUMENTS_GET_PAGINATION_URL;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        paginatedURL = paginatedURL.replace(RestApiConstants.APIID_PARAM, apiId);
        return paginatedURL;
    }

    /** Returns the paginated url for tiers
     *
     * @param tierLevel tier level (api/application or resource)
     * @param tierLevel   tier level (api/application or resource)
     * @param offset starting index
     * @param limit max number of objects returned
     * @return constructed paginated url
     */
    public static String getTiersPaginatedURL(String tierLevel, Integer offset, Integer limit) {
        String paginatedURL = RestApiConstants.TIERS_GET_PAGINATION_URL;
        paginatedURL = paginatedURL.replace(RestApiConstants.TIER_LEVEL_PARAM, tierLevel);
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        return paginatedURL;
    }

    /** Returns the paginated url for tags
     *
     * @param offset starting index
     * @param limit max number of objects returned
     * @return constructed paginated url
     */
    public static String getTagsPaginatedURL(Integer offset, Integer limit) {
        String paginatedURL = RestApiConstants.TAGS_GET_PAGINATION_URL;
        paginatedURL = paginatedURL.replace(RestApiConstants.LIMIT_PARAM, String.valueOf(limit));
        paginatedURL = paginatedURL.replace(RestApiConstants.OFFSET_PARAM, String.valueOf(offset));
        return paginatedURL;
    }

    /**
     * Checks whether the list of tiers are valid given the all valid tiers
     * 
     * @param allTiers All defined tiers
     * @param currentTiers tiers to check if they are a subset of defined tiers
     * @return null if there are no invalid tiers or returns the set of invalid tiers if there are any
     */
    public static List<String> getInvalidTierNames(Set<Tier> allTiers, List<String> currentTiers) {
        List<String> invalidTiers = new ArrayList<>();
        for (String tierName : currentTiers) {
            boolean isTierValid = false;
            for (Tier definedTier : allTiers) {
                if (tierName.equals(definedTier.getName())) {
                    isTierValid = true;
                    break;
                }
            }
            if (!isTierValid) {
                invalidTiers.add(tierName);
            }
        }
        return invalidTiers;
    }

    /**
     * Search the tier in the given collection of Tiers. Returns it if it is included there. Otherwise return null
     *
     * @param tiers    Tier Collection
     * @param tierName Tier to find
     * @return Matched tier with its name
     */
    public static Tier findTier(Collection<Tier> tiers, String tierName) {
        for (Tier tier : tiers) {
            if (tier.getName() != null && tierName != null && tier.getName().equals(tierName)) {
                return tier;
            }
        }
        return null;
    }

    /**
     * Following 3 methods are temporary added to rest API Util
     * Ideally they should move to DCR, RR and Introspection API implementation
     *
     * @param api
     * @param swagger
     * @return
     */
    public static boolean registerResource(API api, String swagger) {

        APIDefinition definitionFromSwagger20 = new APIDefinitionFromSwagger20();
        Set<URITemplate> uriTemplates = null;
        try {
            uriTemplates = definitionFromSwagger20.getURITemplates(api, swagger);
        } catch (APIManagementException e) {
            log.error("Error while parsing swagger content to get URI Templates" + e.getMessage());
        }
        api.setUriTemplates(uriTemplates);
        KeyManager keyManager = KeyManagerHolder.getKeyManagerInstance();
        Map registeredResource = null;
        try {
            registeredResource = keyManager.getResourceByApiId(api.getId().toString());
        } catch (APIManagementException e) {
            log.error("Error while getting registered resources for API: " + api.getId().toString() + e.getMessage());
        }
        //Add new resource if not exist
        if (registeredResource == null) {
            boolean isNewResourceRegistered = false;
            try {
                isNewResourceRegistered = keyManager.registerNewResource(api, null);
            } catch (APIManagementException e) {
                log.error("Error while registering new resource for API: " + api.getId().toString() + e.getMessage());
            }
            if (!isNewResourceRegistered) {
                log.error("New resource not registered for API: " + api.getId());
            }
        }
        //update existing resource
        else {
            try {
                keyManager.updateRegisteredResource(api, registeredResource);
            } catch (APIManagementException e) {
                log.error("Error while updating resource");
            }
        }
        return true;
    }

    public static OAuthApplicationInfo registerOAuthApplication(OAuthAppRequest appRequest) {
        //Create Oauth Application - Dynamic client registration service
        AMDefaultKeyManagerImpl impl = new AMDefaultKeyManagerImpl();
        OAuthApplicationInfo returnedAPP = null;
        try {
            returnedAPP = impl.createApplication(appRequest);
        } catch (APIManagementException e) {
            log.error("Cannot create OAuth application from provided information, for APP name: " +
                    appRequest.getOAuthApplicationInfo().getClientName());
        }
        return returnedAPP;
    }

    public static OAuthApplicationInfo retrieveOAuthApplication(String consumerKey) {
        //Create Oauth Application - Dynamic client registration service
        AMDefaultKeyManagerImpl impl = new AMDefaultKeyManagerImpl();
        OAuthApplicationInfo returnedAPP = null;
        try {
            returnedAPP = impl.retrieveApplication(consumerKey);
        } catch (APIManagementException e) {
            log.error("Error while retrieving OAuth application information for Consumer Key: " + consumerKey);
        }
        return returnedAPP;
    }

    /**
     * This is static method to return URI Templates map of API Store REST API.
     * This content need to load only one time and keep it in memory as content will not change
     * during runtime. Also we cannot change
     *
     * @return URITemplate set associated with API Manager publisher REST API
     */
    public static Set<URITemplate> getStoreAppResourceMapping() {

        API api = new API(new APIIdentifier(RestApiConstants.REST_API_PROVIDER,
                RestApiConstants.REST_API_PUBLISHER_CONTEXT,
                RestApiConstants.REST_API_PUBLISHER_VERSION));

        if (storeResourceMappings != null) {
            return storeResourceMappings;
        } else {

            try {
                String definition = IOUtils.toString(RestApiUtil.class.getResourceAsStream("/store-api.json"), "UTF-8");

            /*
            //if(basePath.contains("/api/am/store/")){
            //this is store API and pick resources accordingly
            //TODO Replace following string with swagger content
            StringBuilder sb = new StringBuilder("{\"basePath\":\"/apim/v1.0.0\",\"host\":\"apis.wso2.com\",\"paths\":{\"/subscriptions/{subscriptionId}\":{\"get\":{\"tags\":[\"Subscription (individual)\",\"Retrieve\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Get subscription details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nSubscription returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Subscription does not exist.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"},\"delete\":{\"tags\":[\"Subscription (individual)\",\"Delete\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Remove subscription\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. \\nResource successfully deleted.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nResource to be deleted does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/applications/{applicationId}\":{\"put\":{\"tags\":[\"Application (individual)\",\"Update\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Update application details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that needs to be updated\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication updated.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nThe resource to be updated does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"},\"get\":{\"tags\":[\"Application (individual)\",\"Retrieve\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Get application details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found.\\nRequested application does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"},\"delete\":{\"tags\":[\"Application (individual)\",\"Delete\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Remove an application\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. \\nResource successfully deleted.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nResource to be deleted does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/applications/generate-keys\":{\"post\":{\"tags\":[\"Application (individual)\",\"Generate Keys\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Generate keys for application\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId-Q\"},{\"schema\":{\"$ref\":\"#/definitions/ApplicationKeyGenerateRequest\"},\"description\":\"Application object the keys of which are to be generated\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/ApplicationKey\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.‚\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nKeys are generated.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nThe resource to be updated does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/apis/{apiId}/documents/{documentId}\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve Document\"],\"description\":\"Get a particular document associated with an API.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/documentId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource.\\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nDocument returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Document does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/tiers\":{\"get\":{\"tags\":[\"Tier Collection\",\"Retrieve\"],\"description\":\"Get available tiers\\n\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"items\":{\"$ref\":\"#/definitions/Tier\"},\"type\":\"array\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nList of tiers returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/apis/{apiId}/documents\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve Documents\"],\"description\":\"Get a list of documents belonging to an API.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"Search condition.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/DocumentList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nDocument list is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/applications\":{\"post\":{\"tags\":[\"Application (individual)\",\"Create\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Create a new application.\\n\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that is to be created.\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"Location of the newly created Application.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"Created. \\nSuccessful response with the newly created object as entity in the body. \\nLocation header contains URL of newly created entity.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"415\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Unsupported media type. \\nThe entity of the request was in a not supported format.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"},\"get\":{\"tags\":[\"Application Collection\",\"Retrieve\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Get a list of applications\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriber\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/ApplicationList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication list returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/apis\":{\"get\":{\"summary\":\"Retrieving APIs\\n\",\"tags\":[\"API Collection\",\"Retrieve\"],\"description\":\"Get a list of available APIs qualifying under a given search condition.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"**Search condition**.\\n\\nYou can search in attributes by using an **\\\"attribute:\\\"** modifier.\\n\\nEg. \\\"provider:wso2\\\" will match an API if the provider of the API is wso2.\\n\\nSupported attribute modifiers are [**version, context, status,\\ndescription, subcontext, doc, provider, tag **]\\n\\nIf no advanced attribute modifier has been specified, search will match the\\ngiven query string against API Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"description\":\"List prototype or production APIs.\\n\",\"name\":\"type\",\"enum\":[\"PRODUCTION\",\"PROTOTYPE\"],\"type\":\"string\",\"in\":\"query\"},{\"description\":\"** Sort expression **\\n\\nA *sort expression* consists of a sequence of names of API \\nproperties concatenated by a '+' or '-' (indicating ascending or \\ndecending order) separated by a comma. The sequence of names \\ncorresponds to a conjunction. \\n\",\"name\":\"sort\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/APIList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nList of qualifying APIs is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/tiers/{tierName}\":{\"get\":{\"tags\":[\"Tier (individual)\",\"Retrieve\"],\"description\":\"Get tier details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nTier returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Tier does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/apis/{apiId}\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve\"],\"description\":\"Get details of an API\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"schema\":{\"$ref\":\"#/definitions/API\"},\"description\":\"OK. \\nRequested API is returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/tags\":{\"get\":{\"tags\":[\"Tag Collection\",\"Retrieve\"],\"description\":\"Get a list of predefined sequences\\n\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"description\":\"**Search condition**.\\n\\n\\nYou can search in attributes by using **\\\"attribute:\\\"** modifier.\\n\\n\\nSupported attribute modifiers are [**apiName,version**]\\n\\n\\nEg. \\\"apiName:phoneVerification\\\" will match if the API Name is\\nphoneVerification.\\n\\n\\nIf no attribute modifier is found search will match the given query string against Tag Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/TagList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nTag list is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}},\"/subscriptions\":{\"post\":{\"tags\":[\"Subscription (individual)\",\"Create\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Add a new subscription\\n\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"description\":\"Subscription object that should to be added\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"Location to the newly created subscription.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"Created. \\nSuccessful response with the newly created object as entity in the body. \\nLocation header contains URL of newly created entity.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error.\\n\"},\"415\":{\"description\":\"Unsupported media type. \\nThe entity of the request was in a not supported format.\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"},\"get\":{\"tags\":[\"Subscription Collection\",\"Retrieve\"],\"x-scope\":\"apim_subscribe_api_scope\",\"description\":\"Get subscription list.\\nThe API Identifier and corresponding Application Identifier\\nthe subscriptions of which are to be returned are passed as parameters.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId-Q\"},{\"$ref\":\"#/parameters/applicationId-Q\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/offset\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/SubscriptionList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nSubscription list returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\\n\"}},\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\"}}},\"schemes\":[\"https\"],\"produces\":[\"application/json\"],\"swagger\":\"2.0\",\"parameters\":{\"apiId\":{\"description\":\"**API ID** consisting of the **UUID** of the API. \\nThe combination of the provider of the API, name of the API and the version is also accepted as a valid API ID.\\nShould be formatted as **provider-name-version**.\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"limit\":{\"default\":25,\"description\":\"Maximum size of resource array to return.\\n\",\"name\":\"limit\",\"type\":\"integer\",\"in\":\"query\"},\"apiId-Q\":{\"description\":\"**API ID** consisting of the **UUID** of the API. \\nThe combination of the provider of the API, name of the API and the version is also accepted as a valid API I.\\nShould be formatted as **provider-name-version**.\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"applicationId\":{\"description\":\"**Application Identifier** consisting of the UUID of the Application.\\n\",\"name\":\"applicationId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Unmodified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header.\\n\",\"name\":\"If-Unmodified-Since\",\"type\":\"string\",\"in\":\"header\"},\"tierName\":{\"description\":\"Tier name\\n\",\"name\":\"tierName\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Match\":{\"description\":\"Validator for conditional requests; based on ETag.\\n\",\"name\":\"If-Match\",\"type\":\"string\",\"in\":\"header\"},\"subscriptionId\":{\"description\":\"Subscription Id\\n\",\"name\":\"subscriptionId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"groupId\":{\"description\":\"Application Group Id\\n\",\"name\":\"groupId\",\"required\":false,\"type\":\"string\",\"in\":\"query\"},\"If-Modified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header of the \\nformerly retrieved variant of the resource.\\n\",\"name\":\"If-Modified-Since\",\"type\":\"string\",\"in\":\"header\"},\"applicationId-Q\":{\"description\":\"**Application Identifier** consisting of the UUID of the Application.\\n\",\"name\":\"applicationId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"If-None-Match\":{\"description\":\"Validator for conditional requests; based on the ETag of the formerly retrieved\\nvariant of the resourec.\\n\",\"name\":\"If-None-Match\",\"type\":\"string\",\"in\":\"header\"},\"offset\":{\"default\":0,\"description\":\"Starting point within the complete list of items qualified.  \\n\",\"name\":\"offset\",\"type\":\"integer\",\"in\":\"query\"},\"Accept\":{\"default\":\"JSON\",\"description\":\"Media types acceptable for the response. Default is JSON.\\n\",\"name\":\"Accept\",\"type\":\"string\",\"in\":\"header\"},\"subscriber\":{\"description\":\"Subscriber username\\n\",\"name\":\"subscriber\",\"required\":false,\"type\":\"string\",\"in\":\"query\"},\"Content-Type\":{\"default\":\"JSON\",\"description\":\"Media type of the entity in the body. Default is JSON.\\n\",\"name\":\"Content-Type\",\"required\":true,\"type\":\"string\",\"in\":\"header\"},\"documentId\":{\"description\":\"**Document Identifier**\\n\",\"name\":\"documentId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"}},\"definitions\":{\"APIList\":{\"title\":\"API List\",\"properties\":{\"count\":{\"description\":\"Number of APIs returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/APIInfo\"},\"type\":\"array\"}}},\"Document\":{\"title\":\"Document\",\"properties\":{\"summary\":{\"type\":\"string\"},\"source\":{\"enum\":[\"INLINE\",\"URL\",\"FILE\"],\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"type\":{\"enum\":[\"HOWTO\",\"SAMPLES\",\"PUBLIC_FORUM\",\"SUPPORT_FORUM\",\"API_MESSAGE_FORMAT\",\"SWAGGER_DOC\",\"OTHER\"],\"type\":\"string\"},\"documentId\":{\"type\":\"string\"}},\"required\":[\"name\",\"type\"]},\"ApplicationKeyGenerateRequest\":{\"title\":\"Application key generation request object\",\"properties\":{\"scopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed scopes for the access token\",\"type\":\"array\"},\"keyType\":{\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"validityTime\":{\"type\":\"string\"},\"callbackUrl\":{\"description\":\"Callback URL\",\"type\":\"string\"},\"accessAllowDomains\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed domains for the access token\",\"type\":\"array\"}}},\"Token\":{\"title\":\"Token details for invoking APIs\",\"properties\":{\"validityTime\":{\"description\":\"Maximum validity time for the access token\",\"format\":\"int64\",\"type\":\"integer\"},\"accessToken\":{\"description\":\"Access token\",\"type\":\"string\"},\"tokenScopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Valid scopes for the access token\",\"type\":\"array\"},\"refreshToken\":{\"description\":\"Refresh token\",\"type\":\"string\"},\"tokenState\":{\"description\":\"Token state\",\"type\":\"string\"}}},\"DocumentList\":{\"title\":\"Document List\",\"properties\":{\"count\":{\"description\":\"Number of Documents returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Document\"},\"type\":\"array\"}}},\"Error\":{\"title\":\"Error object returned with 4XX HTTP status\",\"properties\":{\"message\":{\"description\":\"Error message.\",\"type\":\"string\"},\"error\":{\"items\":{\"$ref\":\"#/definitions/ErrorListItem\"},\"description\":\"If there are more than one error list them out. \\nFor example, list out validation errors by each field.\\n\",\"type\":\"array\"},\"description\":{\"description\":\"A detail description about the error message.\\n\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"},\"moreInfo\":{\"description\":\"Preferably an url with more details about the error.\\n\",\"type\":\"string\"}},\"required\":[\"code\",\"message\"]},\"ErrorListItem\":{\"title\":\"Description of individual errors that may have occurred during a request.\",\"properties\":{\"message\":{\"description\":\"Description about individual errors occurred\\n\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"}},\"required\":[\"code\",\"message\"]},\"TagList\":{\"title\":\"Tag List\",\"properties\":{\"count\":{\"description\":\"Number of Tags returned.\\n\",\"type\":\"integer\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Tag\"},\"type\":\"array\"}}},\"APIInfo\":{\"title\":\"API Info object with basic API details.\",\"properties\":{\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"provider\":{\"description\":\"If the provider value is not given, the user invoking the API will be used as the provider.\\n\",\"type\":\"string\"},\"version\":{\"type\":\"string\"}}},\"API\":{\"title\":\"API object\",\"properties\":{\"id\":{\"description\":\"UUID of the api registry artifact\\n\",\"type\":\"string\"},\"tags\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"status\":{\"type\":\"string\"},\"transport\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"isDefaultVersion\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"tiers\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"provider\":{\"description\":\"If the provider value is not given user invoking the api will be used as the provider.\\n\",\"type\":\"string\"},\"businessInformation\":{\"properties\":{\"businessOwnerEmail\":{\"type\":\"string\"},\"technicalOwnerEmail\":{\"type\":\"string\"},\"technicalOwner\":{\"type\":\"string\"},\"businessOwner\":{\"type\":\"string\"}}},\"apiDefinition\":{\"description\":\"Swagger definition of the API which contains details about URI templates and scopes\\n\",\"type\":\"string\"},\"version\":{\"type\":\"string\"}},\"required\":[\"name\",\"context\",\"version\",\"apiDefinition\"]},\"Tier\":{\"title\":\"Tier\",\"properties\":{\"continueOnQuotaReach\":{\"description\":\"By making this attribute to true, you are capabale of sending requests \\neven if the request count exceeded within a unit time\\n\",\"type\":\"boolean\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"attributes\":{\"description\":\"Custom attributes added to the tier policy\\n\",\"additionalProperties\":{\"type\":\"string\"},\"type\":\"object\"},\"requestCount\":{\"description\":\"Maximum number of requests which can be sent within a provided unit time\\n\",\"type\":\"number\"},\"unitTime\":{\"type\":\"number\"},\"billingPlan\":{\"description\":\"This attribute declares whether this tier is available under commercial or free\\n\",\"type\":\"string\"}},\"required\":[\"name\"]},\"Subscription\":{\"title\":\"Subscription\",\"properties\":{\"apiId\":{\"type\":\"string\"},\"status\":{\"enum\":[\"BLOCKED\",\"PROD_ONLY_BLOCKED\",\"UNBLOCKED\",\"ON_HOLD\",\"REJECTED\"],\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"tier\":{\"type\":\"string\"},\"subscriptionId\":{\"type\":\"string\"}},\"required\":[\"subscriptionId\"]},\"ApplicationKey\":{\"title\":\"Application key details\",\"properties\":{\"consumerKey\":{\"description\":\"Consumer key of the application\",\"type\":\"string\"},\"keyType\":{\"description\":\"Key type\",\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"token\":{\"description\":\"Token details object\",\"$ref\":\"#/definitions/Token\"},\"keyState\":{\"description\":\"State of the key generation of the application\",\"type\":\"string\"},\"consumerSecret\":{\"description\":\"Consumer secret of the application\",\"type\":\"string\"},\"supportedGrantTypes\":{\"items\":{\"type\":\"string\"},\"description\":\"Supported grant types for the application\",\"type\":\"array\"}}},\"Tag\":{\"title\":\"Tag\",\"properties\":{\"weight\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]},\"Application\":{\"title\":\"Application\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"keys\":{\"items\":{\"$ref\":\"#/definitions/ApplicationKey\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"description\":\"If subscriber is not given user invoking the API will be taken as the subscriber.\\n\",\"type\":\"string\"}},\"required\":[\"applicationId\",\"name\",\"subscriber\",\"throttlingTier\"]},\"ApplicationInfo\":{\"title\":\"Application info object with basic application details\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"type\":\"string\"}}},\"SubscriptionList\":{\"title\":\"Subscription List\",\"properties\":{\"count\":{\"description\":\"Number of Subscriptions returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Subscription\"},\"type\":\"array\"}}},\"ApplicationList\":{\"title\":\"Application List\",\"properties\":{\"count\":{\"description\":\"Number of applications returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/ApplicationInfo\"},\"type\":\"array\"}}}},\"consumes\":[\"application/json\"],\"info\":{\"title\":\"sdfsdfsdfdsf\",\"description\":\"This document specifies a **RESTful API** for WSO2 **API Manager**.\\n\\nYou can find the source of this API definition \\n[here](https://github.com/hevayo/restful-apim). \\nIt was written with [swagger 2](http://swagger.io/).\\n\",\"license\":{\"name\":\"Apache 2.0\",\"url\":\"http://www.apache.org/licenses/LICENSE-2.0.html\"},\"contact\":{\"email\":\"architecture@wso2.com\",\"name\":\"WSO2\",\"url\":\"http://wso2.com/products/api-manager/\"},\"version\":\"0.9.0\"},\"x-wso2-security\":{\"apim\":{\"x-wso2-scopes\":[{\"name\":\"apim_subscribe_api_scope\",\"description\":\"apim_subscribe_api_scope\",\"key\":\"apim_subscribe_api_scope\",\"roles\":\"Internal/everyone\"}]}}}");
            */

                APIDefinition definitionFromSwagger20 = new APIDefinitionFromSwagger20();
                //Get URL templates from swagger content we created
                storeResourceMappings = definitionFromSwagger20.getURITemplates(api, definition);
            } catch (APIManagementException e) {
                log.error("Error while reading resource mappings for API: " + api.getId().getApiName());
            } catch (IOException e) {
                log.error("Error while reading the swagger definition for API: " + api.getId().getApiName());
            }
            return storeResourceMappings;
        }
    }


    /**
     * This is static method to return URI Templates map of API Publisher REST API.
     * This content need to load only one time and keep it in memory as content will not change
     * during runtime. Also we cannot change
     *
     * @return URITemplate set associated with API Manager publisher REST API
     */
    public static Set<URITemplate> getPublisherAppResourceMapping() {

        API api = new API(new APIIdentifier(RestApiConstants.REST_API_PROVIDER, RestApiConstants.REST_API_STORE_CONTEXT,
                RestApiConstants.REST_API_STORE_VERSION));

        if (publisherResourceMappings != null) {
            return publisherResourceMappings;
        } else {
            //if(basePath.contains("/api/am/store/")){
            //this is store API and pick resources accordingly
            try {
                String definition = IOUtils
                        .toString(RestApiUtil.class.getResourceAsStream("/publisher-api.json"), "UTF-8");

            /*
            //TODO Replace following string with swagger content
            StringBuilder sb = new StringBuilder("{\"basePath\":\"/apim/v1.0.0\",\"host\":\"apis.wso2.com\",\"paths\":{\"/subscriptions/{subscriptionId}\":{\"get\":{\"tags\":[\"Subscription (individual)\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get subscription details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nSubscription returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Subscription does not exist.\\n\"}}},\"delete\":{\"tags\":[\"Subscription (individual)\",\"Delete\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Remove subscription\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. \\nResource successfully deleted.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nResource to be deleted does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}}}},\"/applications/{applicationId}\":{\"put\":{\"tags\":[\"Application (individual)\",\"Update\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update application details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that needs to be updated\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication updated.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nThe resource to be updated does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}}},\"get\":{\"tags\":[\"Application (individual)\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get application details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found.\\nRequested application does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}}},\"delete\":{\"tags\":[\"Application (individual)\",\"Delete\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Remove an application\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. \\nResource successfully deleted.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nResource to be deleted does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}}}},\"/applications/generate-keys\":{\"post\":{\"tags\":[\"Application (individual)\",\"Generate Keys\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Generate keys for application\\n\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId-Q\"},{\"schema\":{\"$ref\":\"#/definitions/ApplicationKeyGenerateRequest\"},\"description\":\"Application object the keys of which are to be generated\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/ApplicationKey\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.‚\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nKeys are generated.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nThe resource to be updated does not exist.\\n\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. \\nThe request has not been performed because one of the preconditions is not met.\\n\"}}}},\"/apis/{apiId}/documents/{documentId}\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve Document\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a particular document associated with an API.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/documentId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource.\\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nDocument returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Document does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}}}},\"/tiers\":{\"get\":{\"tags\":[\"Tier Collection\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get available tiers\\n\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"items\":{\"$ref\":\"#/definitions/Tier\"},\"type\":\"array\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nList of tiers returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}}}},\"/apis/{apiId}/documents\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve Documents\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of documents belonging to an API.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"Search condition.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/DocumentList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nDocument list is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}}}},\"/applications\":{\"post\":{\"tags\":[\"Application (individual)\",\"Create\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Create a new application.\\n\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that is to be created.\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"Location of the newly created Application.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"Created. \\nSuccessful response with the newly created object as entity in the body. \\nLocation header contains URL of newly created entity.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error\\n\"},\"415\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Unsupported media type. \\nThe entity of the request was in a not supported format.\\n\"}}},\"get\":{\"tags\":[\"Application Collection\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of applications\\n\",\"parameters\":[{\"$ref\":\"#/parameters/subscriber\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/ApplicationList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nApplication list returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported.\\n\"}}}},\"/apis\":{\"get\":{\"summary\":\"Retrieving APIs\\n\",\"tags\":[\"API Collection\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of available APIs qualifying under a given search condition.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"**Search condition**.\\n\\nYou can search in attributes by using an **\\\"attribute:\\\"** modifier.\\n\\nEg. \\\"provider:wso2\\\" will match an API if the provider of the API is wso2.\\n\\nSupported attribute modifiers are [**version, context, status,\\ndescription, subcontext, doc, provider, tag **]\\n\\nIf no advanced attribute modifier has been specified, search will match the\\ngiven query string against API Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"description\":\"List prototype or production APIs.\\n\",\"name\":\"type\",\"enum\":[\"PRODUCTION\",\"PROTOTYPE\"],\"type\":\"string\",\"in\":\"query\"},{\"description\":\"** Sort expression **\\n\\nA *sort expression* consists of a sequence of names of API \\nproperties concatenated by a '+' or '-' (indicating ascending or \\ndecending order) separated by a comma. The sequence of names \\ncorresponds to a conjunction. \\n\",\"name\":\"sort\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/APIList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. \\nList of qualifying APIs is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}}}},\"/tiers/{tierName}\":{\"get\":{\"tags\":[\"Tier (individual)\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get tier details\\n\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional reuquests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nTier returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested Tier does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported.\\n\"}}}},\"/apis/{apiId}\":{\"get\":{\"tags\":[\"API (individual)\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get details of an API\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"schema\":{\"$ref\":\"#/definitions/API\"},\"description\":\"OK. \\nRequested API is returned\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. \\nRequested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. \\nThe requested media type is not supported\\n\"}},\"x-scope\":\"apim_subscribe_api_scope\"}},\"/tags\":{\"get\":{\"tags\":[\"Tag Collection\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of predefined sequences\\n\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"description\":\"**Search condition**.\\n\\n\\nYou can search in attributes by using **\\\"attribute:\\\"** modifier.\\n\\n\\nSupported attribute modifiers are [**apiName,version**]\\n\\n\\nEg. \\\"apiName:phoneVerification\\\" will match if the API Name is\\nphoneVerification.\\n\\n\\nIf no attribute modifier is found search will match the given query string against Tag Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/TagList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nTag list is returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\\n\"}}}},\"/subscriptions\":{\"post\":{\"tags\":[\"Subscription (individual)\",\"Create\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Add a new subscription\\n\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"description\":\"Subscription object that should to be added\\n\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request.\\n\",\"type\":\"string\"},\"Location\":{\"description\":\"Location to the newly created subscription.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"Created. \\nSuccessful response with the newly created object as entity in the body. \\nLocation header contains URL of newly created entity.\\n\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. \\nInvalid request or validation error.\\n\"},\"415\":{\"description\":\"Unsupported media type. \\nThe entity of the request was in a not supported format.\\n\"}}},\"get\":{\"tags\":[\"Subscription Collection\",\"Retrieve\"],\"x-auth-type\":\"Application & Application User\",\"x-scope\":\"apim_subscribe_api_scope\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get subscription list.\\nThe API Identifier and corresponding Application Identifier\\nthe subscriptions of which are to be returned are passed as parameters.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/apiId-Q\"},{\"$ref\":\"#/parameters/applicationId-Q\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/offset\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/SubscriptionList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. \\nUsed by caches, or in conditional requests.\\n\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\\n\",\"type\":\"string\"}},\"description\":\"OK. \\nSubscription list returned.\\n\"},\"304\":{\"description\":\"Not Modified. \\nEmpty body because the client has already the latest version of the requested resource.\\n\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\\n\"}}}}},\"schemes\":[\"https\"],\"x-wso2-security\":{\"apim\":{\"x-wso2-scopes\":[{\"description\":\"apim_subscribe_api_scope\",\"roles\":\"admin\",\"name\":\"apim_subscribe_api_scope\",\"key\":\"apim_subscribe_api_scope\"}]}},\"produces\":[\"application/json\"],\"swagger\":\"2.0\",\"parameters\":{\"apiId\":{\"description\":\"**API ID** consisting of the **UUID** of the API. \\nThe combination of the provider of the API, name of the API and the version is also accepted as a valid API ID.\\nShould be formatted as **provider-name-version**.\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"limit\":{\"default\":25,\"description\":\"Maximum size of resource array to return.\\n\",\"name\":\"limit\",\"type\":\"integer\",\"in\":\"query\"},\"apiId-Q\":{\"description\":\"**API ID** consisting of the **UUID** of the API. \\nThe combination of the provider of the API, name of the API and the version is also accepted as a valid API I.\\nShould be formatted as **provider-name-version**.\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"applicationId\":{\"description\":\"**Application Identifier** consisting of the UUID of the Application.\\n\",\"name\":\"applicationId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Unmodified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header.\\n\",\"name\":\"If-Unmodified-Since\",\"type\":\"string\",\"in\":\"header\"},\"tierName\":{\"description\":\"Tier name\\n\",\"name\":\"tierName\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Match\":{\"description\":\"Validator for conditional requests; based on ETag.\\n\",\"name\":\"If-Match\",\"type\":\"string\",\"in\":\"header\"},\"subscriptionId\":{\"description\":\"Subscription Id\\n\",\"name\":\"subscriptionId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"groupId\":{\"description\":\"Application Group Id\\n\",\"name\":\"groupId\",\"required\":false,\"type\":\"string\",\"in\":\"query\"},\"If-Modified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header of the \\nformerly retrieved variant of the resource.\\n\",\"name\":\"If-Modified-Since\",\"type\":\"string\",\"in\":\"header\"},\"applicationId-Q\":{\"description\":\"**Application Identifier** consisting of the UUID of the Application.\\n\",\"name\":\"applicationId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"If-None-Match\":{\"description\":\"Validator for conditional requests; based on the ETag of the formerly retrieved\\nvariant of the resourec.\\n\",\"name\":\"If-None-Match\",\"type\":\"string\",\"in\":\"header\"},\"offset\":{\"default\":0,\"description\":\"Starting point within the complete list of items qualified.  \\n\",\"name\":\"offset\",\"type\":\"integer\",\"in\":\"query\"},\"Accept\":{\"default\":\"JSON\",\"description\":\"Media types acceptable for the response. Default is JSON.\\n\",\"name\":\"Accept\",\"type\":\"string\",\"in\":\"header\"},\"subscriber\":{\"description\":\"Subscriber username\\n\",\"name\":\"subscriber\",\"required\":false,\"type\":\"string\",\"in\":\"query\"},\"Content-Type\":{\"default\":\"JSON\",\"description\":\"Media type of the entity in the body. Default is JSON.\\n\",\"name\":\"Content-Type\",\"required\":true,\"type\":\"string\",\"in\":\"header\"},\"documentId\":{\"description\":\"**Document Identifier**\\n\",\"name\":\"documentId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"}},\"definitions\":{\"APIList\":{\"title\":\"API List\",\"properties\":{\"count\":{\"description\":\"Number of APIs returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/APIInfo\"},\"type\":\"array\"}}},\"Document\":{\"title\":\"Document\",\"properties\":{\"summary\":{\"type\":\"string\"},\"source\":{\"enum\":[\"INLINE\",\"URL\",\"FILE\"],\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"type\":{\"enum\":[\"HOWTO\",\"SAMPLES\",\"PUBLIC_FORUM\",\"SUPPORT_FORUM\",\"API_MESSAGE_FORMAT\",\"SWAGGER_DOC\",\"OTHER\"],\"type\":\"string\"},\"documentId\":{\"type\":\"string\"}},\"required\":[\"name\",\"type\"]},\"ApplicationKeyGenerateRequest\":{\"title\":\"Application key generation request object\",\"properties\":{\"scopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed scopes for the access token\",\"type\":\"array\"},\"keyType\":{\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"validityTime\":{\"type\":\"string\"},\"callbackUrl\":{\"description\":\"Callback URL\",\"type\":\"string\"},\"accessAllowDomains\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed domains for the access token\",\"type\":\"array\"}}},\"Token\":{\"title\":\"Token details for invoking APIs\",\"properties\":{\"validityTime\":{\"description\":\"Maximum validity time for the access token\",\"format\":\"int64\",\"type\":\"integer\"},\"accessToken\":{\"description\":\"Access token\",\"type\":\"string\"},\"tokenScopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Valid scopes for the access token\",\"type\":\"array\"},\"refreshToken\":{\"description\":\"Refresh token\",\"type\":\"string\"},\"tokenState\":{\"description\":\"Token state\",\"type\":\"string\"}}},\"DocumentList\":{\"title\":\"Document List\",\"properties\":{\"count\":{\"description\":\"Number of Documents returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Document\"},\"type\":\"array\"}}},\"Error\":{\"title\":\"Error object returned with 4XX HTTP status\",\"properties\":{\"message\":{\"description\":\"Error message.\",\"type\":\"string\"},\"error\":{\"items\":{\"$ref\":\"#/definitions/ErrorListItem\"},\"description\":\"If there are more than one error list them out. \\nFor example, list out validation errors by each field.\\n\",\"type\":\"array\"},\"description\":{\"description\":\"A detail description about the error message.\\n\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"},\"moreInfo\":{\"description\":\"Preferably an url with more details about the error.\\n\",\"type\":\"string\"}},\"required\":[\"code\",\"message\"]},\"ErrorListItem\":{\"title\":\"Description of individual errors that may have occurred during a request.\",\"properties\":{\"message\":{\"description\":\"Description about individual errors occurred\\n\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"}},\"required\":[\"code\",\"message\"]},\"TagList\":{\"title\":\"Tag List\",\"properties\":{\"count\":{\"description\":\"Number of Tags returned.\\n\",\"type\":\"integer\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Tag\"},\"type\":\"array\"}}},\"APIInfo\":{\"title\":\"API Info object with basic API details.\",\"properties\":{\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"provider\":{\"description\":\"If the provider value is not given, the user invoking the API will be used as the provider.\\n\",\"type\":\"string\"},\"version\":{\"type\":\"string\"}}},\"API\":{\"title\":\"API object\",\"properties\":{\"id\":{\"description\":\"UUID of the api registry artifact\\n\",\"type\":\"string\"},\"tags\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"status\":{\"type\":\"string\"},\"transport\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"isDefaultVersion\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"tiers\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"provider\":{\"description\":\"If the provider value is not given user invoking the api will be used as the provider.\\n\",\"type\":\"string\"},\"businessInformation\":{\"properties\":{\"businessOwnerEmail\":{\"type\":\"string\"},\"technicalOwnerEmail\":{\"type\":\"string\"},\"technicalOwner\":{\"type\":\"string\"},\"businessOwner\":{\"type\":\"string\"}}},\"apiDefinition\":{\"description\":\"Swagger definition of the API which contains details about URI templates and scopes\\n\",\"type\":\"string\"},\"version\":{\"type\":\"string\"}},\"required\":[\"name\",\"context\",\"version\",\"apiDefinition\"]},\"Tier\":{\"title\":\"Tier\",\"properties\":{\"continueOnQuotaReach\":{\"description\":\"By making this attribute to true, you are capabale of sending requests \\neven if the request count exceeded within a unit time\\n\",\"type\":\"boolean\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"attributes\":{\"description\":\"Custom attributes added to the tier policy\\n\",\"additionalProperties\":{\"type\":\"string\"},\"type\":\"object\"},\"requestCount\":{\"description\":\"Maximum number of requests which can be sent within a provided unit time\\n\",\"type\":\"number\"},\"unitTime\":{\"type\":\"number\"},\"billingPlan\":{\"description\":\"This attribute declares whether this tier is available under commercial or free\\n\",\"type\":\"string\"}},\"required\":[\"name\"]},\"Subscription\":{\"title\":\"Subscription\",\"properties\":{\"apiId\":{\"type\":\"string\"},\"status\":{\"enum\":[\"BLOCKED\",\"PROD_ONLY_BLOCKED\",\"UNBLOCKED\",\"ON_HOLD\",\"REJECTED\"],\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"tier\":{\"type\":\"string\"},\"subscriptionId\":{\"type\":\"string\"}},\"required\":[\"subscriptionId\"]},\"ApplicationKey\":{\"title\":\"Application key details\",\"properties\":{\"consumerKey\":{\"description\":\"Consumer key of the application\",\"type\":\"string\"},\"keyType\":{\"description\":\"Key type\",\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"token\":{\"description\":\"Token details object\",\"$ref\":\"#/definitions/Token\"},\"keyState\":{\"description\":\"State of the key generation of the application\",\"type\":\"string\"},\"consumerSecret\":{\"description\":\"Consumer secret of the application\",\"type\":\"string\"},\"supportedGrantTypes\":{\"items\":{\"type\":\"string\"},\"description\":\"Supported grant types for the application\",\"type\":\"array\"}}},\"Tag\":{\"title\":\"Tag\",\"properties\":{\"weight\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]},\"Application\":{\"title\":\"Application\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"keys\":{\"items\":{\"$ref\":\"#/definitions/ApplicationKey\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"description\":\"If subscriber is not given user invoking the API will be taken as the subscriber.\\n\",\"type\":\"string\"}},\"required\":[\"applicationId\",\"name\",\"subscriber\",\"throttlingTier\"]},\"ApplicationInfo\":{\"title\":\"Application info object with basic application details\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"type\":\"string\"}}},\"SubscriptionList\":{\"title\":\"Subscription List\",\"properties\":{\"count\":{\"description\":\"Number of Subscriptions returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Subscription\"},\"type\":\"array\"}}},\"ApplicationList\":{\"title\":\"Application List\",\"properties\":{\"count\":{\"description\":\"Number of applications returned.\\n\",\"type\":\"integer\"},\"previous\":{\"description\":\"Link to the previous subset of resources qualified. \\nEmpty if current subset is the first subset returned.\\n\",\"type\":\"string\"},\"next\":{\"description\":\"Link to the next subset of resources qualified. \\nEmpty if no more resources are to be returned.\\n\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/ApplicationInfo\"},\"type\":\"array\"}}}},\"consumes\":[\"application/json\"],\"info\":{\"title\":\"asdsdasdasdasd\",\"description\":\"This document specifies a **RESTful API** for WSO2 **API Manager**.\\n\\nYou can find the source of this API definition \\n[here](https://github.com/hevayo/restful-apim). \\nIt was written with [swagger 2](http://swagger.io/).\\n\",\"license\":{\"name\":\"Apache 2.0\",\"url\":\"http://www.apache.org/licenses/LICENSE-2.0.html\"},\"contact\":{\"email\":\"architecture@wso2.com\",\"name\":\"WSO2\",\"url\":\"http://wso2.com/products/api-manager/\"},\"version\":\"0.9.0\"}}");
            */

                APIDefinition definitionFromSwagger20 = new APIDefinitionFromSwagger20();
                //Get URL templates from swagger content we created
                publisherResourceMappings = definitionFromSwagger20.getURITemplates(api, definition);
            } catch (APIManagementException e) {
                log.error("Error while reading resource mappings for API: " + api.getId().getApiName());
            } catch (IOException e) {
                log.error("Error while reading the swagger definition for API: " + api.getId().getApiName());
            }
            return publisherResourceMappings;
        }
    }

    /**
     * @param message        CXF message to be extract auth header
     * @param pattern        Pattern to extract access token
     * @param authHeaderName transport header name which contains authentication information
     * @return access token string according to provided pattern name and auth header name
     */
    public static String extractOAuthAccessTokenFromMessage(Message message, Pattern pattern, String authHeaderName) {
        String authHeader = null;
        String headerString = ((ArrayList) ((TreeMap) (message.get(Message.PROTOCOL_HEADERS))).get(authHeaderName)).get(0).toString();
        Matcher matcher = pattern.matcher(headerString);
        if (matcher.find()) {
            authHeader = headerString.substring(matcher.end());
        }
        return authHeader;
    }


    public static Set<Scope> getPublisherScopes() {
        Set<Scope> returnScopes = new HashSet<Scope>();
        //if(basePath.contains("/api/am/store/")){
        //this is store API and pick resources accordingly
        //TODO Replace following string with swagger content
        StringBuilder sb = new StringBuilder("{\"basePath\":\"/publisher/v1.0.0\",\"host\":\"apis.wso2.com\",\"paths\":{\"/subscriptions/{subscriptionId}\":{\"put\":{\"x-auth-type\":\"Application & Application User\",\"scopes\":{\"write:pets\":\"modify pets in your account\",\"read:pets\":\"read your pets\"},\"x-scope\":\"vvvvvvvvv\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update subscription details\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"description\":\"Subscription object that needs to be updated\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"$ref\":\"#/definitions/Subscription\",\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Subscription updated\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get subscription details\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Subscription returned\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested Subscription does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}},\"delete\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Remove subscription\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. Resource successfully deleted.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Resource to be deleted does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/tiers/{tierName}/update-permission\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update tier permission\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"schema\":{\"$ref\":\"#/definitions/TierPermission\"},\"name\":\"permissions\",\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"items\":{\"$ref\":\"#/definitions/Tier\"},\"type\":\"array\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the modified tier. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the tier has been modified. Used by caches, or in conditional requests.\",\"type\":\"string\"}},\"description\":\"OK. Successfully updated tier permissions\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"401\":{\"schema\":{\"items\":{\"$ref\":\"#/definitions/Error\"},\"type\":\"array\"},\"description\":\"Unauthorized. User not allowed to update tier permission\"},\"403\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Forbidden. The request must be conditional but no condition has been specified.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested tier does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/apis/change-lifecycle\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Change the lifecycle of an API\",\"parameters\":[{\"description\":\"New lifecycle state of the API.\",\"name\":\"newState\",\"type\":\"string\",\"in\":\"formData\"},{\"name\":\"publishToGateway\",\"type\":\"string\",\"in\":\"formData\"},{\"name\":\"resubscription\",\"type\":\"string\",\"in\":\"formData\"},{\"$ref\":\"#/parameters/apiId-Q\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the changed API. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the API lifecycle has been modified the last time. Used by caches, or in conditional requests.\",\"type\":\"string\"}},\"description\":\"OK. Lifecycle changed successfully.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/apis/{apiId}/documents/{documentId}\":{\"put\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update document details.\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"description\":\"Document Id\",\"name\":\"documentId\",\"format\":\"integer\",\"required\":true,\"type\":\"number\",\"in\":\"path\"},{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"description\":\"Document object that needs to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the updated document.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Document updated\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a particular document associated with an API.\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"description\":\"Document Id\",\"name\":\"documentId\",\"format\":\"integer\",\"required\":true,\"type\":\"number\",\"in\":\"path\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/API\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Document returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested Document does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}},\"delete\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Delete a document of an API\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"description\":\"Document Id\",\"name\":\"documentId\",\"format\":\"integer\",\"required\":true,\"type\":\"number\",\"in\":\"path\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. Resource successfully deleted.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Resource to be deleted does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/block-subscription\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Block a subscription.\",\"parameters\":[{\"$ref\":\"#/parameters/subscriptionId-Q\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the blocked subscription. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the subscription has been blocked. Used by caches, or in conditional requests.\",\"type\":\"string\"}},\"description\":\"OK. Subscription was blocked successfully.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested subscription does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/apis/{apiId}/documents\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Add a new document to an API\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"description\":\"Document object that needs to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Document\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Location\":{\"description\":\"Location to the newly created Document.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created Document object as entity in the body. Location header contains URL of newly added document.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"415\":{\"description\":\"Unsupported media type. The entity of the request was in a not supported format.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of documents belonging to an API.\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"Search condition.\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"title\":\"DocumentList\",\"properties\":{\"count\":{\"type\":\"string\"},\"previous\":{\"description\":\"Link to previous page. Empty if current page is first page.\",\"type\":\"string\"},\"next\":{\"description\":\"Link to next page. Empty if no more documents are to be returned.\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Document\"},\"type\":\"array\"}}},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Document list is returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/apis/copy-api\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Create a new API by copying an existing API\",\"parameters\":[{\"description\":\"Version of the new API.\",\"name\":\"newVersion\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/apiId-Q\"}],\"responses\":{\"201\":{\"headers\":{\"Location\":{\"description\":\"The URL of the newly created API.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created API as entity in the body. Location header contains URL of newly created API.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. API to copy does not exist.\"}}}},\"/apis\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Create a new API\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/API\"},\"description\":\"API object that needs to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/API\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created object as entity in the body. Location header contains URL of newly created entity.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error.\"},\"415\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Unsupported Media Type. The entity of the request was in a not supported format.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"summary\":\"Retrieving APIs\\n\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of available APIs qualifying under a given search condition.\\n\",\"parameters\":[{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"description\":\"**Search condition**.\\n\\nYou can search in attributes by using an **\\\"attribute:\\\"** modifier.\\n\\nEg. \\\"provider:wso2\\\" will match an API if the provider of the API is wso2.\\n\\nSupported attribute modifiers are [**version, context, status,\\ndescription, subcontext, doc, provider, tag **]\\n\\nIf no advanced attribute modifier has been specified, search will match the\\ngiven query string against API Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"},{\"description\":\"List prototype or production APIs.\\n\",\"name\":\"type\",\"enum\":[\"PRODUCTION\",\"PROTOTYPE\"],\"type\":\"string\",\"in\":\"query\"},{\"description\":\"** Sort expression **\\n\\nA *sort expression* consists of a sequence of names of API \\nproperties concatenated by a '+' or '-' (indicating ascending or \\ndecending order) separated by a comma. The sequence of names \\ncorresponds to a conjunction. \\n\",\"name\":\"sort\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/APIList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. List of qualifying APIs is returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/apis/{apiId}\":{\"put\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update an existing API\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"schema\":{\"$ref\":\"#/definitions/API\"},\"description\":\"API object that needs to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/API\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Successful response with updated API object\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"403\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Forbidden. The request must be conditional but no condition has been specified.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get details of an API\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"schema\":{\"$ref\":\"#/definitions/API\"},\"description\":\"OK Requested API is returned\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}},\"delete\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Delete an existing API\",\"parameters\":[{\"$ref\":\"#/parameters/apiId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. Resource successfully deleted.\"},\"403\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Forbidden. The request must be conditional but no condition has been specified.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Resource to be deleted does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/applications/{applicationId}\":{\"put\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update application details\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that needs to be updated\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Application updated.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get application details\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Application returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Requested application does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}},\"delete\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Remove an application\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. Resource successfully deleted.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Resource to be deleted does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"}]},\"/applications/{applicationId}/generate-keys\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Generate keys for application\",\"parameters\":[{\"$ref\":\"#/parameters/applicationId\"},{\"schema\":{\"$ref\":\"#/definitions/ApplicationKeyGenerateRequest\"},\"description\":\"Application Key Generation object that includes request parameters\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Specified Production or Sandbox keys generated.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/tiers\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Add a new tier\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"description\":\"Subscription object that should to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Location\":{\"description\":\"Location to the newly created tier.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created object as entity in the body. Location header contains URL of newly created entity.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"415\":{\"description\":\"Unsupported media type. The entity of the request was in a not supported format.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get available tiers\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"items\":{\"$ref\":\"#/definitions/Tier\"},\"type\":\"array\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. List of tiers returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/applications\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Create a new application\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"description\":\"Application object that is to be created\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Application\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Location\":{\"description\":\"Location of the newly created Application.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created object as entity in the body. Location header contains URL of newly created entity.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"415\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Unsupported media type. The entity of the request was in a not supported format.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of applications\",\"parameters\":[{\"$ref\":\"#/parameters/subscriber\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/limit\"},{\"$ref\":\"#/parameters/offset\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/ApplicationList\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Application list returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/environments\":{\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of gateway environments configured previously\",\"parameters\":[{\"description\":\"Will return environment list for the provided API\",\"name\":\"apiId\",\"type\":\"string\",\"in\":\"query\"}],\"responses\":{\"200\":{\"schema\":{\"title\":\"Environment List\",\"properties\":{\"count\":{\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Environment\"},\"type\":\"array\"}}},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. environment list is returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/tiers/{tierName}\":{\"put\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Update tier details\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"description\":\"Tier object that needs to be modified\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Location\":{\"description\":\"The URL of the newly created resource.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Subscription updated.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. The resource to be updated does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get tier details\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"$ref\":\"#/parameters/If-Modified-Since\"}],\"responses\":{\"200\":{\"schema\":{\"$ref\":\"#/definitions/Tier\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Last-Modified\":{\"description\":\"Date and time the resource has been modifed the last time. Used by caches, or in conditional reuquests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. tier returned\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested Subscription does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}},\"delete\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Remove a tier\",\"parameters\":[{\"$ref\":\"#/parameters/tierName\"},{\"$ref\":\"#/parameters/If-Match\"},{\"$ref\":\"#/parameters/If-Unmodified-Since\"}],\"responses\":{\"200\":{\"description\":\"OK. Resource successfully deleted.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Resource to be deleted does not exist.\"},\"412\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Precondition Failed. The request has not been performed because one of the preconditions is not met.\"}}}},\"/tags\":{\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get a list of tags\",\"parameters\":[{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"},{\"description\":\"**Search condition**.\\n\\nYou can search in attributes by using **\\\"attribute:\\\"** modifier.\\n\\nSupported attribute modifiers are [**apiName,version**]\\n\\nEg. \\\"apiName:phoneVerification\\\" will match if the API Name is phoneVerification.\\n\\nIf no attribute modifier is found search will match the given query string against Tag Name.\\n\",\"name\":\"query\",\"type\":\"string\",\"in\":\"query\"}],\"responses\":{\"200\":{\"schema\":{\"title\":\"Tag List\",\"properties\":{\"count\":{\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Tag\"},\"type\":\"array\"}}},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. tag list is returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"404\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Found. Requested API does not exist.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}},\"/subscriptions\":{\"post\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Add a new subscription\",\"parameters\":[{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"description\":\"Subscription object that should to be added\",\"name\":\"body\",\"required\":true,\"in\":\"body\"},{\"$ref\":\"#/parameters/Content-Type\"}],\"responses\":{\"201\":{\"schema\":{\"$ref\":\"#/definitions/Subscription\"},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional request\",\"type\":\"string\"},\"Location\":{\"description\":\"Location to the newly created subscription.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"Created. Successful response with the newly created object as entity in the body. Location header contains URL of newly created entity.\"},\"400\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Bad Request. Invalid request or validation error\"},\"415\":{\"description\":\"Unsupported media type. The entity of the request was in a not supported format.\"}}},\"get\":{\"x-auth-type\":\"Application & Application User\",\"x-throttling-tier\":\"Unlimited\",\"description\":\"Get subscription list\",\"parameters\":[{\"description\":\"Will return sunscriptions for the provided API\",\"name\":\"apiId\",\"type\":\"string\",\"in\":\"query\"},{\"description\":\"Will return subscriptions for the provided Application\",\"name\":\"applicationId\",\"type\":\"string\",\"in\":\"query\"},{\"$ref\":\"#/parameters/groupId\"},{\"$ref\":\"#/parameters/Accept\"},{\"$ref\":\"#/parameters/If-None-Match\"}],\"responses\":{\"200\":{\"schema\":{\"title\":\"SubscriptionList\",\"properties\":{\"count\":{\"type\":\"string\"},\"previous\":{\"description\":\"Link for previous page. Empty if current page is first page.\",\"type\":\"string\"},\"next\":{\"description\":\"Link for next page. Empty if no more subscriptions are to be returned.\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/Subscription\"},\"type\":\"array\"}}},\"headers\":{\"ETag\":{\"description\":\"Entity Tag of the response resource. Used by caches, or in conditional requests.\",\"type\":\"string\"},\"Content-Type\":{\"description\":\"The content type of the body.\",\"type\":\"string\"}},\"description\":\"OK. Subscription list returned.\"},\"304\":{\"description\":\"Not Modified. Empty body because the client has already the latest version of the requested resource.\"},\"406\":{\"schema\":{\"$ref\":\"#/definitions/Error\"},\"description\":\"Not Acceptable. The requested media type is not supported\"}}}}},\"schemes\":[\"https\"],\"x-wso2-security\":{\"apim\":{\"x-wso2-scopes\":[{\"description\":\"vvvvvvvvv\",\"roles\":\"admin\",\"name\":\"vvvvvvvvv\",\"key\":\"vvvvvvvvv\"}]}},\"produces\":[\"application/json\"],\"swagger\":\"2.0\",\"parameters\":{\"apiId\":{\"description\":\"**API ID** consisting of the name of the API, the identifier of the version and of the provider of the API. \\nShould be formatted as **name/version/provider**\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"limit\":{\"description\":\"Maximum size of API array to return.\",\"name\":\"limit\",\"format\":\"integer\",\"required\":true,\"type\":\"number\",\"in\":\"query\"},\"apiId-Q\":{\"description\":\"**API ID** consisting of the name of the API, the identifier of the version and of the provider of the API. \\nShould be formatted as **name/version/provider**\\n\",\"name\":\"apiId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"applicationId\":{\"description\":\"Application Id\",\"name\":\"applicationId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Unmodified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header.\",\"name\":\"If-Unmodified-Since\",\"type\":\"string\",\"in\":\"header\"},\"subscriptionId-Q\":{\"description\":\"Subscription Id\",\"name\":\"subscriptionId\",\"required\":true,\"type\":\"string\",\"in\":\"query\"},\"tierName\":{\"description\":\"Tier name\",\"name\":\"tierName\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"If-Match\":{\"description\":\"Validator for conditional requests; based on ETag.\",\"name\":\"If-Match\",\"type\":\"string\",\"in\":\"header\"},\"subscriptionId\":{\"description\":\"Subscription Id\",\"name\":\"subscriptionId\",\"required\":true,\"type\":\"string\",\"in\":\"path\"},\"groupId\":{\"description\":\"Application Group Id\",\"name\":\"groupId\",\"required\":false,\"type\":\"string\",\"in\":\"query\"},\"If-Modified-Since\":{\"description\":\"Validator for conditional requests; based on Last Modified header.\",\"name\":\"If-Modified-Since\",\"type\":\"string\",\"in\":\"header\"},\"If-None-Match\":{\"description\":\"Validator for conditional requests; based on ETag.\",\"name\":\"If-None-Match\",\"type\":\"string\",\"in\":\"header\"},\"offset\":{\"description\":\"Starting point of the item list.\",\"name\":\"offset\",\"format\":\"integer\",\"required\":true,\"type\":\"number\",\"in\":\"query\"},\"Content-Type\":{\"description\":\"Media type of the entity in the request body. Should denote XML or JSON, default is JSON.\",\"name\":\"Content-Type\",\"type\":\"string\",\"in\":\"header\"},\"Accept\":{\"description\":\"Media types acceptable for the response. Should denote XML or JSON, default is JSON.\",\"name\":\"Accept\",\"type\":\"string\",\"in\":\"header\"},\"subscriber\":{\"description\":\"Subscriber username\",\"name\":\"subscriber\",\"required\":false,\"type\":\"string\",\"in\":\"query\"}},\"definitions\":{\"Environment\":{\"title\":\"Environment\",\"properties\":{\"showInApiConsole\":{\"type\":\"boolean\"},\"name\":{\"type\":\"string\"},\"endpoints\":{\"$ref\":\"#/definitions/EnvironmentEndpoints\"},\"type\":{\"type\":\"string\"},\"serverUrl\":{\"type\":\"string\"}},\"required\":[\"name\",\"type\",\"serverUrl\",\"endpoints\",\"showInApiConsole\"]},\"APIList\":{\"title\":\"APIList\",\"properties\":{\"count\":{\"type\":\"integer\"},\"previous\":{\"description\":\"Link for previous page. Empty if current page is first page.\",\"type\":\"string\"},\"next\":{\"description\":\"Link for next page. Empty if no more APIs to be returned.\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/APIInfo\"},\"type\":\"array\"}}},\"Document\":{\"title\":\"Document\",\"properties\":{\"summary\":{\"type\":\"string\"},\"source\":{\"enum\":[\"INLINE\",\"URL\",\"FILE\"],\"type\":\"string\"},\"visibility\":{\"enum\":[\"OWNER_ONLY\",\"PRIVATE\",\"API_LEVEL\"],\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"type\":{\"enum\":[\"HOWTO\",\"SAMPLES\",\"PUBLIC_FORUM\",\"SUPPORT_FORUM\",\"API_MESSAGE_FORMAT\",\"SWAGGER_DOC\",\"OTHER\"],\"type\":\"string\"},\"documentId\":{\"type\":\"string\"}},\"required\":[\"name\",\"type\"]},\"ApplicationKeyGenerateRequest\":{\"title\":\"Application key generation request object\",\"properties\":{\"scopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed scopes for the access token\",\"type\":\"array\"},\"keyType\":{\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"validityTime\":{\"type\":\"string\"},\"callbackUrl\":{\"description\":\"Callback URL\",\"type\":\"string\"},\"accessAllowDomains\":{\"items\":{\"type\":\"string\"},\"description\":\"Allowed domains for the access token\",\"type\":\"array\"}}},\"Token\":{\"title\":\"Token details for invoking APIs\",\"properties\":{\"validityTime\":{\"description\":\"Maximum validity time for the access token\",\"format\":\"int64\",\"type\":\"integer\"},\"accessToken\":{\"description\":\"Access token\",\"type\":\"string\"},\"tokenScopes\":{\"items\":{\"type\":\"string\"},\"description\":\"Valid scopes for the access token\",\"type\":\"array\"},\"refreshToken\":{\"description\":\"Refresh token\",\"type\":\"string\"},\"tokenState\":{\"description\":\"Token state\",\"type\":\"string\"}}},\"Error\":{\"title\":\"Error object returned with 4XX HTTP status\",\"properties\":{\"message\":{\"description\":\"Error message.\",\"type\":\"string\"},\"error\":{\"items\":{\"$ref\":\"#/definitions/ErrorListItem\"},\"description\":\"If there are more than one error list them out. Ex. list out validation errors by each field.\",\"type\":\"array\"},\"description\":{\"description\":\"A detail description about the error message.\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"},\"moreInfo\":{\"description\":\"Preferably an url with more details about the error.\",\"type\":\"string\"}},\"required\":[\"code\",\"message\"]},\"EnvironmentEndpoints\":{\"title\":\"Environment Endpoints\",\"properties\":{\"https\":{\"description\":\"HTTPS environment URL\",\"type\":\"string\"},\"http\":{\"description\":\"HTTP environment URL\",\"type\":\"string\"}}},\"ErrorListItem\":{\"title\":\"Description of individual errors that may have occurred during a request.\",\"properties\":{\"message\":{\"description\":\"Description about individual errors occurred\",\"type\":\"string\"},\"code\":{\"format\":\"int64\",\"type\":\"integer\"}},\"required\":[\"code\",\"message\"]},\"APIInfo\":{\"title\":\"API info object with basic api details.\",\"properties\":{\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"provider\":{\"description\":\"If the provider value is not given user invoking the api will be used as the provider.\",\"type\":\"string\"},\"type\":{\"enum\":[\"REST\",\"SOAP\"],\"type\":\"string\"},\"version\":{\"type\":\"string\"}}},\"API\":{\"title\":\"API object\",\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"responseCaching\":{\"type\":\"string\"},\"visibility\":{\"enum\":[\"PUBLIC\",\"PRIVATE\",\"RESTRICTED\",\"CONTROLLED\"],\"type\":\"string\"},\"status\":{\"type\":\"string\"},\"isDefaultVersion\":{\"type\":\"boolean\"},\"sequences\":{\"items\":{\"$ref\":\"#/definitions/Sequence\"},\"type\":\"array\"},\"visibleRoles\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"destinationStatsEnabled\":{\"type\":\"string\"},\"provider\":{\"description\":\"If the provider value is not given user invoking the api will be used as the provider.\",\"type\":\"string\"},\"type\":{\"enum\":[\"REST\",\"SOAP\"],\"type\":\"string\"},\"endpointConfig\":{\"type\":\"string\"},\"version\":{\"type\":\"string\"},\"id\":{\"description\":\"UUID of the api registry artifact\",\"type\":\"string\"},\"subscriptionAvailability\":{\"enum\":[\"current_tenant\",\"all_tenants\",\"specific_tenants\"],\"type\":\"string\"},\"subscriptionAvailableTenants\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"visibleTenants\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"transport\":{\"items\":{\"enum\":[\"http\",\"https\"],\"type\":\"string\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"tiers\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"cacheTimeout\":{\"type\":\"integer\"},\"businessInformation\":{\"properties\":{\"businessOwnerEmail\":{\"type\":\"string\"},\"technicalOwnerEmail\":{\"type\":\"string\"},\"technicalOwner\":{\"type\":\"string\"},\"businessOwner\":{\"type\":\"string\"}}},\"apiDefinition\":{\"description\":\"Swagger definition of the API which contains details about URI templates and scopes\",\"type\":\"string\"}},\"required\":[\"name\",\"context\",\"version\",\"apiDefinition\"]},\"Tier\":{\"title\":\"Tier\",\"properties\":{\"continueOnQuotaReach\":{\"description\":\"By making this attribute to true, you are capabale of sending requests even request count exceeded within a unit time\",\"type\":\"boolean\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"attributes\":{\"description\":\"custom attributes added to the tier policy\",\"additionalProperties\":{\"type\":\"string\"},\"type\":\"object\"},\"requestCount\":{\"description\":\"Maximum number of requests which can be sent within a provided unit time\",\"type\":\"number\"},\"unitTime\":{\"type\":\"number\"},\"billingPlan\":{\"description\":\"This attribute declares whether this tier is available under commercial or free\",\"type\":\"string\"}},\"required\":[\"name\"]},\"Subscription\":{\"title\":\"Subscription\",\"properties\":{\"apiId\":{\"type\":\"string\"},\"status\":{\"enum\":[\"BLOCKED\",\"PROD_ONLY_BLOCKED\",\"UNBLOCKED\",\"ON_HOLD\",\"REJECTED\"],\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"tier\":{\"type\":\"string\"},\"subscriptionId\":{\"type\":\"string\"}},\"required\":[\"subscriptionId\"]},\"ApplicationKey\":{\"title\":\"Application key details\",\"properties\":{\"consumerKey\":{\"description\":\"Consumer key of the application\",\"type\":\"string\"},\"keyType\":{\"description\":\"Key type\",\"enum\":[\"PRODUCTION\",\"SANDBOX\"],\"type\":\"string\"},\"token\":{\"description\":\"Token details object\",\"$ref\":\"#/definitions/Token\"},\"keyState\":{\"description\":\"State of the key generation of the application\",\"type\":\"string\"},\"consumerSecret\":{\"description\":\"Consumer secret of the application\",\"type\":\"string\"},\"supportedGrantTypes\":{\"items\":{\"type\":\"string\"},\"description\":\"Supported grant types for the application\",\"type\":\"array\"}}},\"Tag\":{\"title\":\"Tag\",\"properties\":{\"weight\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]},\"TierPermission\":{\"title\":\"tierPermission\",\"properties\":{\"roles\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"enableAccess\":{\"type\":\"string\"}}},\"Application\":{\"title\":\"Application\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"keys\":{\"items\":{\"$ref\":\"#/definitions/ApplicationKey\"},\"type\":\"array\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"description\":\"If subscriber is not given user invoking the API will be taken as the subscriber.\",\"type\":\"string\"}},\"required\":[\"applicationId\",\"name\",\"subscriber\",\"throttlingTier\"]},\"ApplicationInfo\":{\"title\":\"Application info object with basic application details\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"callbackUrl\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"applicationId\":{\"type\":\"string\"},\"throttlingTier\":{\"type\":\"string\"},\"subscriber\":{\"type\":\"string\"}}},\"Sequence\":{\"title\":\"Sequence\",\"properties\":{\"name\":{\"type\":\"string\"},\"config\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"}},\"required\":[\"name\"]},\"ApplicationList\":{\"title\":\"ApplicationList\",\"properties\":{\"count\":{\"type\":\"integer\"},\"previous\":{\"description\":\"Link for previous page. Empty if current page is first page.\",\"type\":\"string\"},\"next\":{\"description\":\"Link for next page. Empty if no more APIs to be returned.\",\"type\":\"string\"},\"list\":{\"items\":{\"$ref\":\"#/definitions/ApplicationInfo\"},\"type\":\"array\"}}}},\"consumes\":[\"application/json\"],\"info\":{\"title\":\"WSO2 API Manager\",\"description\":\"This document specifies a **RESTful API** for WSO2 **API Manager**.\\n\\nYou can find the source of this API definition \\n[here](https://github.com/hevayo/restful-apim). \\nIt was written with [swagger 2](http://swagger.io/).\\n\",\"license\":{\"name\":\"Apache 2.0\",\"url\":\"http://www.apache.org/licenses/LICENSE-2.0.html\"},\"contact\":{\"email\":\"architecture@wso2.com\",\"name\":\"WSO2\",\"url\":\"http://wso2.com/products/api-manager/\"},\"version\":\"0.9.0\"}}");
        APIDefinition definitionFromSwagger20 = new APIDefinitionFromSwagger20();
        API api = new API(new APIIdentifier(RestApiConstants.REST_API_PROVIDER, RestApiConstants.REST_API_STORE_CONTEXT,
                RestApiConstants.REST_API_STORE_VERSION));
        //Get URL templates from swagger content we created
        try {
            returnScopes = definitionFromSwagger20.getScopes(sb.toString());
        } catch (APIManagementException e) {
            log.error("Error while reading resource mappings for API: " + api.getId().getApiName());
        }
        return returnScopes;

    }


    private static String removeLeadingAndTrailing(String base) {
        String result = base;
        if (base.startsWith("\"") || base.endsWith("\"")) {
            result = base.replace("\"", "");
        }
        return result.trim();
    }
}
