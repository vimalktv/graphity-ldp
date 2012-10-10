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
package org.graphity.ldp.model.impl;

import com.hp.hpl.jena.ontology.OntModel;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.UriInfo;
import org.graphity.ldp.model.ContainerResource;
import org.graphity.ldp.model.Resource;
import org.graphity.util.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.topbraid.spin.model.SPINFactory;
import org.topbraid.spin.model.Select;
import org.topbraid.spin.vocabulary.SP;

/**
 *
 * @author Martynas Jusevičius <martynas@graphity.org>
 */
public class ContainerResourceBase extends ResourceBase implements ContainerResource
{
    private static final Logger log = LoggerFactory.getLogger(ContainerResourceBase.class);
    
    private Long limit = null;
    private Long offset = null;
    private String orderBy = null;
    private Boolean desc = true;
    
    public ContainerResourceBase(OntModel ontology, UriInfo uriInfo, Request req,
	Long limit, Long offset, String orderBy, Boolean desc)
    {
	super(ontology, uriInfo, req);
	this.limit = limit;
	this.offset = offset;
	this.orderBy = orderBy;
	this.desc = desc;
	
	if (getQueryResource() == null || !(SPINFactory.asQuery(getQueryResource()) instanceof Select))
	    throw new IllegalArgumentException("ContainerResource must have a SELECT query");
    }

    public ContainerResourceBase(Resource resource,
	    Long limit, Long offset, String orderBy, Boolean desc)
    {
	this(resource.getOntology(), resource.getUriInfo(), resource.getRequest(),
		limit, offset, orderBy, desc);
    }

    public QueryBuilder getSelectBuilder()
    {
	QueryBuilder sb = QueryBuilder.fromResource(getQueryResource()).
	    limit(limit).offset(offset);

	/*
	if (orderBy != null)
	{
	    com.hp.hpl.jena.rdf.model.Resource modelVar = getOntology().createResource().addLiteral(SP.varName, "model");
	    Property orderProperty = ResourceFactory.createProperty(orderBy);
	    com.hp.hpl.jena.rdf.model.Resource orderVar = getOntology().createResource().addLiteral(SP.varName, orderProperty.getLocalName());

	    sb.orderBy(orderVar, desc).optional(modelVar, orderProperty, orderVar);
	}
	*/

	return sb;
    }

    @Override
    public QueryBuilder getQueryBuilder()
    {
	if (getQueryResource().getPropertyResourceValue(SP.resultVariables) != null)
	    return QueryBuilder.fromDescribe(getQueryResource().getPropertyResourceValue(SP.resultVariables)).
		subQuery(getSelectBuilder());
	else
	    return QueryBuilder.fromDescribe().subQuery(getSelectBuilder());	    
    }

    @Override
    public Long getLimit()
    {
	return this.limit;
    }

    @Override
    public Long getOffset()
    {
	return this.offset;
    }

    @Override
    public String getOrderBy()
    {
	return this.orderBy;
    }

    @Override
    public Boolean getDesc()
    {
	return this.desc;
    }

}