package io.swagger.core.filter;

import io.swagger.model.ApiDescription;
import io.swagger.oas.models.Components;
import io.swagger.oas.models.OpenAPI;
import io.swagger.oas.models.Operation;
import io.swagger.oas.models.PathItem;
import io.swagger.oas.models.Paths;
import io.swagger.oas.models.media.Schema;
import io.swagger.oas.models.parameters.Parameter;
import io.swagger.oas.models.parameters.RequestBody;
import io.swagger.oas.models.responses.ApiResponse;
import io.swagger.oas.models.responses.ApiResponses;
import io.swagger.oas.models.tags.Tag;
import io.swagger.util.Json;

import javax.xml.ws.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public class SpecFilter {

    public OpenAPI filter(OpenAPI openAPI, OpenAPISpecFilter filter, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        OpenAPI filteredOpenAPI = filterOpenAPI(filter, openAPI, params, cookies, headers);
        if (filteredOpenAPI == null) {
            return filteredOpenAPI;
        }

        OpenAPI clone = new OpenAPI();
        clone.info(filteredOpenAPI.getInfo());
        clone.openapi(filteredOpenAPI.getOpenapi());
        clone.setExtensions(openAPI.getExtensions());
        clone.setExternalDocs(openAPI.getExternalDocs());
        clone.setSecurity(openAPI.getSecurity());
        clone.setServers(openAPI.getServers());
        clone.tags(filteredOpenAPI.getTags() == null ? null : new ArrayList<>(openAPI.getTags()));

        final Set<String> filteredTags = new HashSet<>();
        final Set<String> allowedTags = new HashSet<>();

        Paths clonedPaths = new Paths();
        for (String resourcePath : openAPI.getPaths().keySet()) {
            PathItem pathItem = openAPI.getPaths().get(resourcePath);

            PathItem filteredPathItem = filterPathItem(filter, pathItem, resourcePath, params, cookies, headers);

            if (filteredPathItem != null) {

                PathItem clonedPathItem = new PathItem();
                clonedPathItem.set$ref(filteredPathItem.get$ref());
                clonedPathItem.setDescription(filteredPathItem.getDescription());
                clonedPathItem.setSummary(filteredPathItem.getSummary());
                clonedPathItem.setExtensions(filteredPathItem.getExtensions());
                clonedPathItem.setParameters(filteredPathItem.getParameters());
                clonedPathItem.setServers(filteredPathItem.getServers());

                Map<PathItem.HttpMethod, Operation> ops = filteredPathItem.readOperationsMap();


                for (PathItem.HttpMethod key : ops.keySet()) {
                    Operation op = ops.get(key);
                    final Set<String> tags;
                    clonedPathItem.operation(key, filterOperation(filter, op, resourcePath, key.toString(), params, cookies, headers));
                    op = clonedPathItem.readOperationsMap().get(key);
                    if (op != null) {
                        tags = allowedTags;
                    } else {
                        tags = filteredTags;
                    }
                    if (op.getTags() != null) {
                        tags.addAll(op.getTags());
                    }
                }
                if (!clonedPathItem.readOperations().isEmpty()) {
                    clonedPaths.addPathItem(resourcePath, clonedPathItem);
                }
            }
        }
        clone.paths(clonedPaths);
        final List<Tag> tags = clone.getTags();
        filteredTags.removeAll(allowedTags);
        if (tags != null && !filteredTags.isEmpty()) {
            for (Iterator<Tag> it = tags.iterator(); it.hasNext(); ) {
                if (filteredTags.contains(it.next().getName())) {
                    it.remove();
                }
            }
            if (clone.getTags().isEmpty()) {
                clone.setTags(null);
            }
        }
        if (clone.getComponents() != null) {
            clone.getComponents().setSchemas(filterComponentsSchema(filter, openAPI.getComponents().getSchemas(), params, cookies, headers));
        }

        if (filter.isRemovingUnreferencedDefinitions()) {
            clone = removeBrokenReferenceDefinitions(clone);
        }

        return clone;
    }

    private OpenAPI filterOpenAPI(OpenAPISpecFilter filter, OpenAPI openAPI, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (openAPI != null) {
            Optional<OpenAPI> filteredOpenAPI = filter.filterOpenAPI(openAPI, params, cookies, headers);
            if (filteredOpenAPI.isPresent()) {
                return filteredOpenAPI.get();
            }
        }
        return null;
    }

    private Operation filterOperation(OpenAPISpecFilter filter, Operation operation, String resourcePath, String key, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (operation != null) {
            ApiDescription description = new ApiDescription(resourcePath, key);
            Optional<Operation> filteredOperation = filter.filterOperation(operation, description, params, cookies, headers);
            if (filteredOperation.isPresent()) {
                List<Parameter> filteredParameters = new ArrayList<>();
                Operation filteredOperationGet = filteredOperation.get();
                List<Parameter> parameters = filteredOperationGet.getParameters();
                if (parameters != null) {
                    for (Parameter parameter : parameters) {
                        Parameter filteredParameter = filterParameter(filter, operation, parameter, resourcePath, key, params, cookies, headers);
                        if (filteredParameter != null) {
                            filteredParameters.add(filteredParameter);
                        }
                    }
                }
                filteredOperationGet.setParameters(filteredParameters);

                RequestBody requestBody = filteredOperation.get().getRequestBody();
                if (requestBody != null) {
                    RequestBody filteredRequestBody = filterRequestBody(filter, operation, requestBody, resourcePath, key, params, cookies, headers);
                    filteredOperationGet.setRequestBody(filteredRequestBody);

                }

                ApiResponses responses = filteredOperation.get().getResponses();
                ApiResponses clonedResponses = responses;
                if (responses != null) {
                    responses.forEach((responseKey, response) -> {
                        ApiResponse filteredResponse = filterResponse(filter, operation, response, resourcePath, key, params, cookies, headers);
                        if (filteredResponse != null) {
                            clonedResponses.addApiResponse(responseKey, filteredResponse);
                        }
                    });
                    filteredOperationGet.setResponses(clonedResponses);
                }

                return filteredOperationGet;
            }
        }
        return null;
    }

    private PathItem filterPathItem(OpenAPISpecFilter filter, PathItem pathItem, String resourcePath, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        ApiDescription description = new ApiDescription(resourcePath, null);
        Optional<PathItem> filteredPathItem = filter.filterPathItem(pathItem, description, params, cookies, headers);
        if (filteredPathItem.isPresent()) {
            return filteredPathItem.get();
        }
        return null;
    }

    private Parameter filterParameter(OpenAPISpecFilter filter, Operation operation, Parameter parameter, String resourcePath, String key, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (parameter != null) {
            ApiDescription description = new ApiDescription(resourcePath, key);
            Optional<Parameter> filteredParameter = filter.filterParameter(parameter, operation, description, params, cookies, headers);
            if (filteredParameter.isPresent()) {
                return filteredParameter.get();
            }
        }
        return null;

    }

    private RequestBody filterRequestBody(OpenAPISpecFilter filter, Operation operation, RequestBody requestBody, String resourcePath, String key, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (requestBody != null) {
            ApiDescription description = new ApiDescription(resourcePath, key);
            Optional<RequestBody> filteredRequestBody = filter.filterRequestBody(requestBody, operation, description, params, cookies, headers);
            if (filteredRequestBody.isPresent()) {
                return filteredRequestBody.get();
            }
        }
        return null;

    }

    private ApiResponse filterResponse(OpenAPISpecFilter filter, Operation operation, ApiResponse response, String resourcePath, String key, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (response != null) {
            ApiDescription description = new ApiDescription(resourcePath, key);
            Optional<ApiResponse> filteredResponse = filter.filterResponse(response, operation, description, params, cookies, headers);
            if (filteredResponse.isPresent()) {
                return filteredResponse.get();
            }
        }
        return null;

    }

    private Map<String, Schema> filterComponentsSchema(OpenAPISpecFilter filter, Map<String, Schema> schemasMap, Map<String, List<String>> params, Map<String, String> cookies, Map<String, List<String>> headers) {
        if (schemasMap == null) {
            return null;
        }
        Map<String, Schema> clonedComponentsSchema = new LinkedHashMap<String, Schema>();

        for (String key : clonedComponentsSchema.keySet()) {
            Schema definition = clonedComponentsSchema.get(key);
            Optional<Schema> filteredDefinition = filter.filterSchema(definition, params, cookies, headers);
            if (!filteredDefinition.isPresent()) {
                continue;
            } else {
                Map<String, Schema> clonedProperties = new LinkedHashMap<>();
                if (filteredDefinition.get().getProperties() != null) {
                    for (Object propName : filteredDefinition.get().getProperties().keySet()) {
                        Schema property = (Schema)filteredDefinition.get().getProperties().get((String)propName);
                        if (property != null) {
                            Optional<Schema> filteredProperty = filter.filterSchemaProperty(property, definition, (String)propName, params, cookies, headers);
                            if (filteredProperty.isPresent()) {
                                clonedProperties.put((String)propName, property);
                            }
                        }
                    }
                }

                try {
                    // TODO solve this, and generally handle clone and passing references
                    Schema clonedModel = Json.mapper().readValue(Json.pretty(definition), Schema.class);
                    if (clonedModel.getProperties() != null) {
                        clonedModel.getProperties().clear();
                    }
                    clonedModel.setProperties(clonedProperties);
                    clonedComponentsSchema.put(key, clonedModel);

                } catch (IOException e) {
                    continue;
                }
            }
        }
        return clonedComponentsSchema;
    }

    private OpenAPI aremoveBrokenReferenceDefinitions(OpenAPI clone) {
        Components components = clone.getComponents();
        components.getSchemas().entrySet().removeIf((schema -> !definedSchemas.containsKey(schema.getValue().getType())));
        return clone;
    }

    private OpenAPI removeBrokenReferenceDefinitions (OpenAPI openApi) {

        if (openApi == null || openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return openApi;
        }
        Set<String> referencedDefinitions =  new TreeSet<>();

        if (openApi.getResponses() != null) {
            for (Response response: swagger.getResponses().values()) {
                String propertyRef = getPropertyRef(response.getSchema());
                if (propertyRef != null) {
                    referencedDefinitions.add(propertyRef);
                }
            }
        }
        if (swagger.getParameters() != null) {
            for (Parameter p: swagger.getParameters().values()) {
                if (p instanceof BodyParameter) {
                    BodyParameter bp = (BodyParameter) p;
                    Set<String>  modelRef = getModelRef(bp.getSchema());
                    if (modelRef != null) {
                        referencedDefinitions.addAll(modelRef);
                    }
                }
            }
        }
        if (swagger.getPaths() != null) {
            for (Path path : swagger.getPaths().values()) {
                if (path.getParameters() != null) {
                    for (Parameter p: path.getParameters()) {
                        if (p instanceof BodyParameter) {
                            BodyParameter bp = (BodyParameter) p;
                            Set<String>  modelRef = getModelRef(bp.getSchema());
                            if (modelRef != null) {
                                referencedDefinitions.addAll(modelRef);
                            }
                        }
                    }
                }
                if (path.getOperations() != null) {
                    for (Operation op: path.getOperations()) {
                        if (op.getResponses() != null) {
                            for (Response response: op.getResponses().values()) {
                                String propertyRef = getPropertyRef(response.getSchema());
                                if (propertyRef != null) {
                                    referencedDefinitions.add(propertyRef);
                                }
                            }
                        }
                        if (op.getParameters() != null) {
                            for (Parameter p: op.getParameters()) {
                                if (p instanceof BodyParameter) {
                                    BodyParameter bp = (BodyParameter) p;
                                    Set<String> modelRef = getModelRef(bp.getSchema());
                                    if (modelRef != null) {
                                        referencedDefinitions.addAll(modelRef);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (swagger.getDefinitions() != null) {
            Set<String> nestedReferencedDefinitions =  new TreeSet<String>();
            for (String ref : referencedDefinitions){
                locateReferencedDefinitions(ref, nestedReferencedDefinitions, swagger);
            }
            referencedDefinitions.addAll(nestedReferencedDefinitions);
            swagger.getDefinitions().keySet().retainAll(referencedDefinitions);
        }

        return swagger;
    }

    private void locateReferencedDefinitions (Map<String, Property> props, Set<String> nestedReferencedDefinitions, Swagger swagger) {
        if (props == null) return;
        for (String keyProp: props.keySet()) {
            Property p = props.get(keyProp);
            String ref = getPropertyRef(p);
            if (ref != null) {
                locateReferencedDefinitions(ref, nestedReferencedDefinitions, swagger);
            }
        }
    }

    private void locateReferencedDefinitions(String ref, Set<String> nestedReferencedDefinitions, Swagger swagger) {
        // if not already processed so as to avoid infinite loops
        if (!nestedReferencedDefinitions.contains(ref)) {
            nestedReferencedDefinitions.add(ref);
            Model model = swagger.getDefinitions().get(ref);
            if (model != null) {
                locateReferencedDefinitions(model.getProperties(), nestedReferencedDefinitions, swagger);
            }
        }
    }

    private String getPropertyRef(Property property) {
        if (property instanceof ArrayProperty &&
                ((ArrayProperty) property).getItems() != null) {
            return getPropertyRef(((ArrayProperty) property).getItems());
        } else if (property instanceof MapProperty &&
                ((MapProperty) property).getAdditionalProperties() != null) {
            return getPropertyRef(((MapProperty) property).getAdditionalProperties());
        } else if (property instanceof RefProperty) {
            return ((RefProperty) property).getSimpleRef();
        }
        return null;
    }

    private Set<String> getModelRef(Model model) {
        if (model instanceof ArrayModel &&
                ((ArrayModel) model).getItems() != null) {
            String propertyRef = getPropertyRef(((ArrayModel) model).getItems());
            if (propertyRef != null) {
                return new HashSet<String>(Arrays.asList(propertyRef));
            }
        } else if (model instanceof ComposedModel &&
                ((ComposedModel) model).getAllOf() != null) {
            Set<String> refs = new LinkedHashSet<String>();
            ComposedModel cModel = (ComposedModel) model;
            for (Model ref: cModel.getAllOf()) {
                if (ref instanceof RefModel) {
                    refs.add(((RefModel)ref).getSimpleRef());
                }
            }
            return refs;
        } else if (model instanceof RefModel) {
            return new HashSet<String>(Arrays.asList(((RefModel) model).getSimpleRef()));
        }
        return null;
    }
}
