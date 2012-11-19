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

import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.ontology.*;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.util.LocationMapper;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import java.util.List;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import org.graphity.ldp.model.impl.LinkedDataPageResourceImpl;
import org.graphity.ldp.model.query.ModelResource;
import org.graphity.ldp.model.query.impl.EndpointModelResourceImpl;
import org.graphity.ldp.model.query.impl.QueryModelModelResourceImpl;
import org.graphity.model.ResourceFactory;
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
@Path("{path: .*}")
public class LinkedDataResourceBase extends ResourceFactory implements LinkedDataResource // QueriedResource
{
    private static final Logger log = LoggerFactory.getLogger(LinkedDataResourceBase.class);

    public static List<Variant> VARIANTS = Variant.VariantListBuilder.newInstance().
		mediaTypes(MediaType.APPLICATION_XHTML_XML_TYPE,
			org.graphity.ldp.MediaType.APPLICATION_RDF_XML_TYPE,
			org.graphity.ldp.MediaType.TEXT_TURTLE_TYPE).
		//languages(new Locale("en")).
		add().build();

    private final UriInfo uriInfo;
    private final Request request;
    private final HttpHeaders httpHeaders;
    private final List<Variant> variants;
    private final OntResource ontResource;
    private final ModelResource resource;

    public static OntModel getOntology(UriInfo uriInfo)
    {
	// ResourceConfig.getProperty()
	return getOntology(uriInfo.getBaseUri().toString(), "org/graphity/ldp/vocabulary/graphity-ldp.ttl");
    }
    
    // avoid (double-checked) locking and make state changes transactional with synchronized
    // http://www.ibm.com/developerworks/java/library/j-dcl/index.html
    // https://open.med.harvard.edu/svn/eagle-i-dev/apps/tags/1.0-MS4.0/common/model-jena/src/main/java/org/eaglei/model/jena/EagleIOntDocumentManager.java
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

    public static QueryBuilder getQueryBuilder(OntResource ontResource)
    {
	QueryBuilder queryBuilder;
	
	if (ontResource.hasProperty(Graphity.query))
	{
	    queryBuilder = QueryBuilder.fromResource(ontResource.getPropertyResourceValue(Graphity.query));
	    if (log.isDebugEnabled()) log.debug("OntResource with URI {} has Query Resource {}", ontResource.getURI(), ontResource.getPropertyResourceValue(Graphity.query));
	}
	else
	{
	    queryBuilder = QueryBuilder.fromDescribe(ontResource.getURI(), ontResource.getModel());
	    if (log.isDebugEnabled()) log.debug("OntResource with URI {} gets explicit Query Resource {}", ontResource.getURI(), queryBuilder);
	}

	return queryBuilder;
    }

    public static ModelResource getModelResource(OntResource ontResource,
	    UriInfo uriInfo, Request request, HttpHeaders httpHeaders, List<Variant> variants,
	    Long limit, Long offset, String orderBy, Boolean desc)
    {	
	if (ontResource.hasRDFType(SIOC.CONTAINER))
	{
	    if (log.isDebugEnabled()) log.debug("OntResource is a container, returning page Resource");
	    return new LinkedDataPageResourceImpl(ontResource,
		uriInfo, request, httpHeaders, variants,
		limit, offset, orderBy, desc);
	}
	else
	{
	    QueryBuilder queryBuilder = getQueryBuilder(ontResource);
	    Query query = queryBuilder.build();

	    ontResource.setPropertyValue(Graphity.query, queryBuilder); // Resource alway get a g:query value		

	    if (ontResource.hasProperty(Graphity.service))
	    {
		com.hp.hpl.jena.rdf.model.Resource service = ontResource.getPropertyResourceValue(Graphity.service);
		if (service == null) throw new IllegalArgumentException("SPARQL Service must be a Resource");

		com.hp.hpl.jena.rdf.model.Resource endpoint = service.getPropertyResourceValue(com.hp.hpl.jena.rdf.model.ResourceFactory.
		    createProperty("http://www.w3.org/ns/sparql-service-description#endpoint"));
		if (endpoint == null || endpoint.getURI() == null) throw new IllegalArgumentException("SPARQL Service endpoint must be URI Resource");

		if (log.isDebugEnabled()) log.debug("OntResource with URI: {} has explicit SPARQL endpoint: {}", ontResource.getURI(), endpoint.getURI());

		return new EndpointModelResourceImpl(endpoint.getURI(), query, request, variants);
	    }
	    else
	    {
		if (log.isDebugEnabled())
		{
		    log.debug("OntResource with URI: {} has no explicit SPARQL endpoint, querying its Model", ontResource.getURI());
		    log.debug("Locking getOntResource.getModel() before SPARQL query");
		}
		//synchronized (getOntModel())
		{
		    return new QueryModelModelResourceImpl(ontResource.getOntModel(), query, request, variants);
		}
		//if (log.isDebugEnabled()) log.debug("Unlocking getOntResource.getModel()");
	    }
	}
    }

    public static OntClass matchOntClass(Class<?> cls, OntModel ontModel)
    {
	if (log.isDebugEnabled()) log.debug("Matching @Path annotation {} of Class {}", cls.getAnnotation(Path.class).value(), cls);
	return matchOntClass(cls.getAnnotation(Path.class).value(), ontModel);
    }
    
    public static OntClass matchOntClass(String uriTemplate, OntModel ontModel)
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
	    if (log.isDebugEnabled()) log.debug("URI template {} has no endpoint match in Model {}", uriTemplate, ontModel);
	    return null;   
	}
    }

    public LinkedDataResourceBase(@Context UriInfo uriInfo, @Context Request request, @Context HttpHeaders httpHeaders,
	    @QueryParam("limit") @DefaultValue("20") Long limit,
	    @QueryParam("offset") @DefaultValue("0") Long offset,
	    @QueryParam("order-by") String orderBy,
	    @QueryParam("desc") Boolean desc)
    {
	this(getOntology(uriInfo).createOntResource(uriInfo.getAbsolutePath().toString()),
		uriInfo, request, httpHeaders, VARIANTS,
		limit, offset, orderBy, desc);
    }

    //OntResource ontResource, ModelResource resource,
    protected LinkedDataResourceBase(OntResource ontResource,
	    UriInfo uriInfo, Request request, HttpHeaders httpHeaders, List<Variant> variants,
	    Long limit, Long offset, String orderBy, Boolean desc)
    {
	if (ontResource == null) throw new IllegalArgumentException("OntResource cannot be null");
	if (uriInfo == null) throw new IllegalArgumentException("UriInfo cannot be null");
	if (request == null) throw new IllegalArgumentException("Request cannot be null");
	if (httpHeaders == null) throw new IllegalArgumentException("HttpHeaders cannot be null");
	if (variants == null) throw new IllegalArgumentException("Variants cannot be null");
	
	if (!ontResource.isURIResource()) throw new IllegalArgumentException("OntResource must be URI Resource (not a blank node)");
	this.ontResource = ontResource;
	if (log.isDebugEnabled())
	{
	    log.debug("Creating LinkedDataResource from OntResource with URI: {}", ontResource.getURI());
	    log.debug("List of Variants: {}", variants);
	}

	resource = getModelResource(ontResource, uriInfo, request, httpHeaders, VARIANTS, limit, offset, orderBy, desc);
	if (resource.getModel().isEmpty())
	{
	    if (log.isTraceEnabled()) log.trace("Loaded Model is empty; returnin 404 Not Found");
	    throw new WebApplicationException(Response.Status.NOT_FOUND);
	}

	this.uriInfo = uriInfo;
	this.request = request;
	this.httpHeaders = httpHeaders;
	this.variants = variants;
    }

    @GET
    @Override
    // http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-21#section-5.3.1
    public Response getResponse()
    {
	// Content-Location http://www.w3.org/TR/chips/#cp5.2
	// http://www.w3.org/wiki/HR14aCompromise
	
	if (log.isDebugEnabled()) log.debug("Returning @GET Response");
	return getResource().getResponse();
    }

    public ModelResource getResource()
    {
	return resource;
    }
    
    @Override
    public final String getURI()
    {
	return getOntResource().getURI();
    }

    @Override
    public final EntityTag getEntityTag()
    {
	return getResource().getEntityTag();
    }

    @Override
    public final Model getModel()
    {
	return getResource().getModel();
    }

    @Override
    public final Request getRequest()
    {
	return request;
    }

    public final OntResource getOntResource()
    {
	return ontResource;
    }

    @Override
    public final OntModel getOntModel()
    {
	return getOntResource().getOntModel();
    }
    
    public final UriInfo getUriInfo()
    {
	return uriInfo;
    }

    @Override
    public final List<Variant> getVariants()
    {
	return variants;
    }

    public final HttpHeaders getHttpHeaders()
    {
	return httpHeaders;
    }

    @Override
    public Profile getProfile()
    {
	return getOntResource().getProfile();
    }

    @Override
    public boolean isOntLanguageTerm()
    {
	return getOntResource().isOntLanguageTerm();
    }

    @Override
    public void setSameAs(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().setSameAs(rsrc);
    }

    @Override
    public void addSameAs(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().addSameAs(rsrc);
    }

    @Override
    public OntResource getSameAs()
    {
	return getOntResource().getSameAs();
    }

    @Override
    public ExtendedIterator<? extends com.hp.hpl.jena.rdf.model.Resource> listSameAs()
    {
	return getOntResource().listSameAs();
    }

    @Override
    public boolean isSameAs(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	return getOntResource().isSameAs(rsrc);
    }

    @Override
    public void removeSameAs(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().removeSameAs(rsrc);
    }

    @Override
    public void setDifferentFrom(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().setDifferentFrom(rsrc);
    }

    @Override
    public void addDifferentFrom(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().addDifferentFrom(rsrc);
    }

    @Override
    public OntResource getDifferentFrom()
    {
	return getOntResource().getDifferentFrom();
    }

    @Override
    public ExtendedIterator<? extends com.hp.hpl.jena.rdf.model.Resource> listDifferentFrom()
    {
	return getOntResource().listDifferentFrom();
    }

    @Override
    public boolean isDifferentFrom(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	return getOntResource().isDifferentFrom(rsrc);
    }

    @Override
    public void removeDifferentFrom(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().removeDifferentFrom(rsrc);
    }

    @Override
    public void setSeeAlso(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().setSeeAlso(rsrc);
    }

    @Override
    public void addSeeAlso(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().addSeeAlso(rsrc);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource getSeeAlso()
    {
	return getOntResource().getSeeAlso();
    }

    @Override
    public ExtendedIterator<RDFNode> listSeeAlso()
    {
	return getOntResource().listSeeAlso();
    }

    @Override
    public boolean hasSeeAlso(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	return getOntResource().hasSeeAlso(rsrc);
    }

    @Override
    public void removeSeeAlso(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().removeSeeAlso(rsrc);
    }

    @Override
    public void setIsDefinedBy(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().setIsDefinedBy(rsrc);
    }

    @Override
    public void addIsDefinedBy(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().addIsDefinedBy(rsrc);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource getIsDefinedBy()
    {
	return getOntResource().getIsDefinedBy();
    }

    @Override
    public ExtendedIterator<RDFNode> listIsDefinedBy()
    {
	return getOntResource().listIsDefinedBy();
    }

    @Override
    public boolean isDefinedBy(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	return getOntResource().isDefinedBy(rsrc);
    }

    @Override
    public void removeDefinedBy(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().removeDefinedBy(rsrc);
    }

    @Override
    public void setVersionInfo(String string)
    {
	getOntResource().setVersionInfo(string);
    }

    @Override
    public void addVersionInfo(String string)
    {
	getOntResource().addVersionInfo(string);
    }

    @Override
    public String getVersionInfo()
    {
	return getOntResource().getVersionInfo();
    }

    @Override
    public ExtendedIterator<String> listVersionInfo()
    {
	return getOntResource().listVersionInfo();
    }

    @Override
    public boolean hasVersionInfo(String string)
    {
	return getOntResource().hasVersionInfo(string);
    }

    @Override
    public void removeVersionInfo(String string)
    {
	getOntResource().removeVersionInfo(string);
    }

    @Override
    public void setLabel(String string, String string1)
    {
	getOntResource().setLabel(string, string1);
    }

    @Override
    public void addLabel(String string, String string1)
    {
	getOntResource().addLabel(string, string1);
    }

    @Override
    public void addLabel(Literal ltrl)
    {
	getOntResource().addLabel(ltrl);
    }

    @Override
    public String getLabel(String string)
    {
	return getOntResource().getLabel(string);
    }

    @Override
    public ExtendedIterator<RDFNode> listLabels(String string)
    {
	return getOntResource().listLabels(string);
    }

    @Override
    public boolean hasLabel(String string, String string1)
    {
	return getOntResource().hasLabel(string, string1);
    }

    @Override
    public boolean hasLabel(Literal ltrl)
    {
	return getOntResource().hasLabel(ltrl);
    }

    @Override
    public void removeLabel(String string, String string1)
    {
	getOntResource().removeLabel(string, string1);
    }

    @Override
    public void removeLabel(Literal ltrl)
    {
	getOntResource().removeLabel(ltrl);
    }

    @Override
    public void setComment(String string, String string1)
    {
	getOntResource().setComment(string, string1);
    }

    @Override
    public void addComment(String string, String string1)
    {
	getOntResource().addComment(string, string1);
    }

    @Override
    public void addComment(Literal ltrl)
    {
	getOntResource().addComment(ltrl);
    }

    @Override
    public String getComment(String string)
    {
	return getOntResource().getComment(string);
    }

    @Override
    public ExtendedIterator<RDFNode> listComments(String string)
    {
	return getOntResource().listComments(string);
    }

    @Override
    public boolean hasComment(String string, String string1)
    {
	return getOntResource().hasComment(string, string1);
    }

    @Override
    public boolean hasComment(Literal ltrl)
    {
	return getOntResource().hasComment(ltrl);
    }

    @Override
    public void removeComment(String string, String string1)
    {
	getOntResource().removeComment(string, string1);
    }

    @Override
    public void removeComment(Literal ltrl)
    {
	getOntResource().removeComment(ltrl);
    }

    @Override
    public void setRDFType(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().setRDFType(rsrc);
    }

    @Override
    public void addRDFType(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().addRDFType(rsrc);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource getRDFType()
    {
	return getOntResource().getRDFType();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource getRDFType(boolean bln)
    {
	return getOntResource().getRDFType(bln);
    }

    @Override
    public ExtendedIterator<com.hp.hpl.jena.rdf.model.Resource> listRDFTypes(boolean bln)
    {
	return getOntResource().listRDFTypes(bln);
    }

    @Override
    public boolean hasRDFType(com.hp.hpl.jena.rdf.model.Resource rsrc, boolean bln)
    {
	return getOntResource().hasRDFType(rsrc, bln);
    }

    @Override
    public boolean hasRDFType(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	return getOntResource().hasRDFType(rsrc);
    }

    @Override
    public void removeRDFType(com.hp.hpl.jena.rdf.model.Resource rsrc)
    {
	getOntResource().removeRDFType(rsrc);
    }

    @Override
    public boolean hasRDFType(String string)
    {
	return getOntResource().hasRDFType(string);
    }

    @Override
    public int getCardinality(Property prprt)
    {
	return getOntResource().getCardinality(prprt);
    }

    @Override
    public void setPropertyValue(Property prprt, RDFNode rdfn)
    {
	getOntResource().setPropertyValue(prprt, rdfn);
    }

    @Override
    public RDFNode getPropertyValue(Property prprt)
    {
	return getOntResource().getPropertyValue(prprt);
    }

    @Override
    public NodeIterator listPropertyValues(Property prprt)
    {
	return getOntResource().listPropertyValues(prprt);
    }

    @Override
    public void removeProperty(Property prprt, RDFNode rdfn)
    {
	getOntResource().removeProperty(prprt, rdfn);
    }

    @Override
    public void remove()
    {
	getOntResource().remove();
    }

    @Override
    public OntProperty asProperty()
    {
	return getOntResource().asProperty();
    }

    @Override
    public AnnotationProperty asAnnotationProperty()
    {
	return getOntResource().asAnnotationProperty();
    }

    @Override
    public ObjectProperty asObjectProperty()
    {
	return getOntResource().asObjectProperty();
    }

    @Override
    public DatatypeProperty asDatatypeProperty()
    {
	return getOntResource().asDatatypeProperty();
    }

    @Override
    public Individual asIndividual()
    {
	return getOntResource().asIndividual();
    }

    @Override
    public OntClass asClass()
    {
	return getOntResource().asClass();
    }

    @Override
    public Ontology asOntology()
    {
	return getOntResource().asOntology();
    }

    @Override
    public DataRange asDataRange()
    {
	return getOntResource().asDataRange();
    }

    @Override
    public AllDifferent asAllDifferent()
    {
	return getOntResource().asAllDifferent();
    }

    @Override
    public boolean isProperty()
    {
	return getOntResource().isProperty();
    }

    @Override
    public boolean isAnnotationProperty()
    {
	return getOntResource().isAnnotationProperty();
    }

    @Override
    public boolean isObjectProperty()
    {
	return getOntResource().isObjectProperty();
    }

    @Override
    public boolean isDatatypeProperty()
    {
	return getOntResource().isDatatypeProperty();
    }

    @Override
    public boolean isIndividual()
    {
	return getOntResource().isIndividual();
    }

    @Override
    public boolean isClass()
    {
	return getOntResource().isClass();
    }

    @Override
    public boolean isOntology()
    {
	return getOntResource().isOntology();
    }

    @Override
    public boolean isDataRange()
    {
	return getOntResource().isDataRange();
    }

    @Override
    public boolean isAllDifferent()
    {
	return getOntResource().isAllDifferent();
    }

    @Override
    public AnonId getId()
    {
	return getOntResource().getId();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource inModel(Model model)
    {
	return getOntResource().inModel(model);
    }

    @Override
    public boolean hasURI(String string)
    {
	return getOntResource().hasURI(string);
    }

    @Override
    public String getNameSpace()
    {
	return getOntResource().getNameSpace();
    }

    @Override
    public String getLocalName()
    {
	return getOntResource().getLocalName();
    }

    @Override
    public Statement getRequiredProperty(Property prprt)
    {
	return getOntResource().getRequiredProperty(prprt);
    }

    @Override
    public Statement getProperty(Property prprt)
    {
	return getOntResource().getProperty(prprt);
    }

    @Override
    public StmtIterator listProperties(Property prprt)
    {
	return getOntResource().listProperties(prprt);
    }

    @Override
    public StmtIterator listProperties()
    {
	return getOntResource().listProperties();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, boolean bln)
    {
	return getOntResource().addLiteral(prprt, bln);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, long l)
    {
	return getOntResource().addLiteral(prprt, l);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, char c)
    {
	return getOntResource().addLiteral(prprt, c);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, double d)
    {
	return getOntResource().addLiteral(prprt, d);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, float f)
    {
	return getOntResource().addLiteral(prprt, f);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, Object o)
    {
	return getOntResource().addLiteral(prprt, o);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addLiteral(Property prprt, Literal ltrl)
    {
	return getOntResource().addLiteral(prprt, ltrl);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addProperty(Property prprt, String string)
    {
	return getOntResource().addLiteral(prprt, string);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addProperty(Property prprt, String string, String string1)
    {
	return getOntResource().addProperty(prprt, string, string1);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addProperty(Property prprt, String string, RDFDatatype rdfd)
    {
	return getOntResource().addProperty(prprt, prprt);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource addProperty(Property prprt, RDFNode rdfn)
    {
	return getOntResource().addProperty(prprt, rdfn);
    }

    @Override
    public boolean hasProperty(Property prprt)
    {
	return getOntResource().hasProperty(prprt);
    }

    @Override
    public boolean hasLiteral(Property prprt, boolean bln)
    {
	return getOntResource().hasLiteral(prprt, bln);
    }

    @Override
    public boolean hasLiteral(Property prprt, long l)
    {
	return getOntResource().hasLiteral(prprt, l);
    }

    @Override
    public boolean hasLiteral(Property prprt, char c)
    {
	return getOntResource().hasLiteral(prprt, c);
    }

    @Override
    public boolean hasLiteral(Property prprt, double d)
    {
	return getOntResource().hasLiteral(prprt, d);
    }

    @Override
    public boolean hasLiteral(Property prprt, float f)
    {
	return getOntResource().hasLiteral(prprt, f);
    }

    @Override
    public boolean hasLiteral(Property prprt, Object o)
    {
	return getOntResource().hasLiteral(prprt, o);
    }

    @Override
    public boolean hasProperty(Property prprt, String string)
    {
	return getOntResource().hasProperty(prprt, string);
    }

    @Override
    public boolean hasProperty(Property prprt, String string, String string1)
    {
	return getOntResource().hasProperty(prprt, string, string1);
    }

    @Override
    public boolean hasProperty(Property prprt, RDFNode rdfn)
    {
	return getOntResource().hasProperty(prprt, rdfn);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource removeProperties()
    {
	return getOntResource().removeProperties();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource removeAll(Property prprt)
    {
	return getOntResource().removeAll(prprt);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource begin()
    {
	return getOntResource().begin();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource abort()
    {
	return getOntResource().abort();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource commit()
    {
	return getOntResource().commit();
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource getPropertyResourceValue(Property prprt)
    {
	return getOntResource().getPropertyResourceValue(prprt);
    }

    @Override
    public boolean isAnon()
    {
	return getOntResource().isAnon();
    }

    @Override
    public boolean isLiteral()
    {
	return getOntResource().isLiteral();
    }

    @Override
    public boolean isURIResource()
    {
	return getOntResource().isURIResource();
    }

    @Override
    public boolean isResource()
    {
	return getOntResource().isResource();
    }

    @Override
    public <T extends RDFNode> T as(Class<T> type)
    {
	return getOntResource().as(type);
    }

    @Override
    public <T extends RDFNode> boolean canAs(Class<T> type)
    {
	return getOntResource().canAs(type);
    }

    @Override
    public Object visitWith(RDFVisitor rdfv)
    {
	return getOntResource().visitWith(rdfv);
    }

    @Override
    public com.hp.hpl.jena.rdf.model.Resource asResource()
    {
	return getOntResource().asResource();
    }

    @Override
    public Literal asLiteral()
    {
	return getOntResource().asLiteral();
    }

    @Override
    public Node asNode()
    {
	return getOntResource().asNode();
    }

}