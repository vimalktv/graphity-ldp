/*
 * Copyright (C) 2012 Martynas Jusevičius <martynas@graphity.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graphity.ldp.model;

import com.hp.hpl.jena.ontology.*;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.util.LocationMapper;
import java.util.List;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.*;
import org.graphity.ldp.model.impl.LinkedDataPageResourceImpl;
import org.graphity.model.query.QueriedResource;
import org.graphity.util.QueryBuilder;
import org.graphity.util.locator.PrefixMapper;
import org.graphity.util.manager.DataManager;
import org.graphity.vocabulary.Graphity;
import org.graphity.vocabulary.SIOC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Martynas Jusevičius <martynas@graphity.org>
 */
public class ResourceBase extends LDPResourceBase implements QueriedResource
{
    private static final Logger log = LoggerFactory.getLogger(ResourceBase.class);

    public static OntModel getOntology(UriInfo uriInfo)
    {
	// ResourceConfig.getProperty()
	return getOntology(uriInfo.getBaseUri().toString(), "org/graphity/ldp/vocabulary/graphity-ldp.ttl");
    }
    
    public static OntModel getOntology(String baseUri, String ontologyPath)
    {
	//synchronized (OntDocumentManager.getInstance())
	{
	    //if (!OntDocumentManager.getInstance().getFileManager().hasCachedModel(baseUri)) // not cached
	    {	    
		if (log.isDebugEnabled())
		{
		    log.debug("Ontology not cached, reading from file: {}", ontologyPath);
		    log.debug("DataManager.get().getLocationMapper(): {}", DataManager.get().getLocationMapper());
		    log.debug("Adding name/altName mapping: {} altName: {} ", baseUri, ontologyPath);
		}
		OntDocumentManager.getInstance().addAltEntry(baseUri, ontologyPath);

		LocationMapper mapper = OntDocumentManager.getInstance().getFileManager().getLocationMapper();
		if (log.isDebugEnabled()) log.debug("Adding prefix/altName mapping: {} altName: {} ", baseUri, ontologyPath);
		((PrefixMapper)mapper).addAltPrefixEntry(baseUri, ontologyPath);	    
	    }
	    //else
		//if (log.isDebugEnabled()) log.debug("Ontology already cached, returning cached instance");

	    OntModel ontModel = OntDocumentManager.getInstance().getOntology(baseUri, OntModelSpec.OWL_MEM_RDFS_INF);
	    if (log.isDebugEnabled()) log.debug("Ontology size: {}", ontModel.size());
	    return ontModel;
	}
    }

    public ResourceBase(@Context UriInfo uriInfo, @Context Request request, @Context HttpHeaders httpHeaders,
	    @QueryParam("limit") @DefaultValue("20") Long limit,
	    @QueryParam("offset") @DefaultValue("0") Long offset,
	    @QueryParam("order-by") String orderBy,
	    @QueryParam("desc") Boolean desc)
    {
	super(getOntology(uriInfo).createOntResource(uriInfo.getAbsolutePath().toString()),
		uriInfo, request, httpHeaders, VARIANTS,
		limit, offset, orderBy, desc);
    }

    public ResourceBase(OntModel ontModel,
	    UriInfo uriInfo, Request request, HttpHeaders httpHeaders,
	    Long limit, Long offset, String orderBy, Boolean desc)
    {
	super(ontModel.createOntResource(uriInfo.getAbsolutePath().toString()),
		uriInfo, request, httpHeaders, VARIANTS,
		limit, offset, orderBy, desc);
    }
    
    @Override
    public Model describe()
    {
	if (log.isDebugEnabled()) log.debug("Creating default QueryBuilder for DESCRIBE <{}>", getURI());
	QueryBuilder describe = QueryBuilder.fromDescribe(getOntResource().getURI(), getOntResource().getModel());
	Model model = ModelFactory.createDefaultModel();

	// CONFIGURED BEHAVIOUR START
	if (hasRDFType(SIOC.CONTAINER))
	{
	    if (log.isDebugEnabled()) log.debug("OntResource is a container, returning page Resource");
	    LinkedDataResourceBase page = new LinkedDataPageResourceImpl(this,
		getUriInfo(), getRequest(), getHttpHeaders(), getVariants(),
		getLimit(), getOffset(), getOrderBy(), getDesc());

	    model.add(page.describe());
	}

	if (!hasProperty(Graphity.query)) // Resource always gets a g:query value
	{
	    if (log.isDebugEnabled()) log.debug("OntResource with URI {} gets explicit Query Resource {}", getURI(), describe);
	    setPropertyValue(Graphity.query, describe);
	}
	else // override default DESCRIBE query
	{
	    if (log.isDebugEnabled()) log.debug("OntResource with URI {} has explicit Query: {}", getURI(), getQuery());

	    if (hasProperty(Graphity.service))
	    {
		com.hp.hpl.jena.rdf.model.Resource service = getPropertyResourceValue(Graphity.service);
		if (service == null) throw new IllegalArgumentException("SPARQL Service must be a Resource");

		com.hp.hpl.jena.rdf.model.Resource endpoint = service.getPropertyResourceValue(com.hp.hpl.jena.rdf.model.ResourceFactory.
		    createProperty("http://www.w3.org/ns/sparql-service-description#endpoint"));
		if (endpoint == null || endpoint.getURI() == null) throw new IllegalArgumentException("SPARQL Service endpoint must be URI Resource");

		if (log.isDebugEnabled()) log.debug("OntResource with URI: {} has explicit SPARQL endpoint: {}", getURI(), endpoint.getURI());

		// query endpoint whenever g:service is present
		model.add(getModelResource(endpoint.getURI(), getQuery()).getModel());
	    }
	    else
	    {
		if (log.isDebugEnabled()) log.debug("OntResource with URI: {} has no explicit SPARQL endpoint, querying its OntModel", getURI());
		model.add(getModelResource(getOntModel(), getQuery()).getModel());
	    }
	}
	// CONFIGURED BEHAVIOUR END

	// what about g:service here?
	if (log.isDebugEnabled()) log.debug("Adding default DESCRIBE Model");
	model.add(super.describe());
	
	return model;
    }

    @Override
    public Query getQuery()
    {
	return getQueryBuilder().build();
    }

    public final QueryBuilder getQueryBuilder()
    {
	if (log.isDebugEnabled()) log.debug("OntResource with URI {} has Query Resource {}", getURI(), getPropertyResourceValue(Graphity.query));
	return QueryBuilder.fromResource(getPropertyResourceValue(Graphity.query));
    }

    public OntClass matchOntClass(OntModel ontModel)
    {
	if (log.isDebugEnabled()) log.debug("Matching @Path annotation {} of Class {}", getClass().getAnnotation(Path.class).value(), getClass());
	return matchOntClass(getClass().getAnnotation(Path.class).value(), ontModel);
    }

    public OntClass matchOntClass(Class<?> cls, OntModel ontModel)
    {
	if (log.isDebugEnabled()) log.debug("Matching @Path annotation {} of Class {}", cls.getAnnotation(Path.class).value(), cls);
	return matchOntClass(cls.getAnnotation(Path.class).value(), ontModel);
    }
    
    public OntClass matchOntClass(String uriTemplate, OntModel ontModel)
    {
	if (uriTemplate == null) throw new IllegalArgumentException("Item endpoint class must have a @Path annotation");
	
	if (log.isDebugEnabled()) log.debug("Matching URI template template {} against Model {}", uriTemplate, ontModel);
	Property utProp = ontModel.createProperty("http://purl.org/linked-data/api/vocab#uriTemplate");
	ResIterator it = ontModel.listResourcesWithProperty(utProp, uriTemplate);

	if (it.hasNext())
	{
	    com.hp.hpl.jena.rdf.model.Resource match = it.next();
	    if (!match.canAs(OntClass.class)) throw new IllegalArgumentException("Resource matching this URI template is not an OntClass");
	    
	    if (log.isDebugEnabled()) log.debug("URI template {} matched endpoint OntClass {}", uriTemplate, match.as(OntClass.class));
	    return match.as(OntClass.class);
	}
	else
	{
	    if (log.isDebugEnabled()) log.debug("URI template {} has no OntClass match in OntModel {}", uriTemplate, ontModel);
	    return null;   
	}
    }

}
