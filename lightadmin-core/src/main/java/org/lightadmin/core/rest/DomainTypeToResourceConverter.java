package org.lightadmin.core.rest;

import org.lightadmin.core.config.BeanNameGenerator;
import org.lightadmin.core.config.DomainTypeAdministrationConfiguration;
import org.lightadmin.core.config.GlobalAdministrationConfiguration;
import org.lightadmin.core.config.GlobalAdministrationConfigurationAware;
import org.lightadmin.core.persistence.metamodel.DomainTypeAttributeMetadata;
import org.lightadmin.core.persistence.metamodel.DomainTypeEntityMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.rest.repository.jpa.JpaRepositoryExporter;
import org.springframework.data.rest.repository.jpa.JpaRepositoryMetadata;
import org.springframework.data.rest.webmvc.EntityResource;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Resource;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.PluralAttribute;
import java.io.Serializable;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Maps.newLinkedHashMap;
import static com.google.common.collect.Sets.newLinkedHashSet;
import static org.springframework.data.rest.core.util.UriUtils.buildUri;

public class DomainTypeToResourceConverter implements Converter<Object, Resource>, GlobalAdministrationConfigurationAware {

	private JpaRepositoryExporter jpaRepositoryExporter;

	private GlobalAdministrationConfiguration configuration;

	@Override
	public Resource convert( final Object source ) {
		if ( source == null ) {
			return new Resource<Object>( source );
		}

		final Class domainType = source.getClass();

		final DomainTypeAdministrationConfiguration domainTypeConfiguration = configuration.forDomainType( domainType );

		if ( domainTypeConfiguration == null ) {
			return new Resource<Object>( source );
		}

		final DomainTypeEntityMetadata<? extends DomainTypeAttributeMetadata> entityMetadata = domainTypeConfiguration.getDomainTypeEntityMetadata();
		final JpaRepositoryMetadata repositoryMetadata = jpaRepositoryExporter.repositoryMetadataFor( domainType );

//		final URI selfUri = selfURI( domainType, entityMetadata, source );

		final Set<Link> links = newLinkedHashSet();

		final Collection<? extends DomainTypeAttributeMetadata> attributes = entityMetadata.getAttributes();
		for ( DomainTypeAttributeMetadata attribute : attributes ) {
			final String attributeName = attribute.getName();
			DomainTypeAdministrationConfiguration domainConfiguration;
			if ( null != ( domainConfiguration = configuration.forDomainType( attributeType( attribute.getAttribute() ) ) ) ) {
//				URI uri = buildUri( selfUri, attributeName );
//				String rel = repositoryMetadata.rel() + "." + domainConfiguration.getDomainTypeName() + "." + attributeName;
//				links.add( new Link( uri.toString(), rel ) );
			}
		}
//		links.add( new Link( selfUri.toString(), "self" ) );

		Map<String, Object> entityDto = newLinkedHashMap();

		entityDto.put( entityMetadata.getIdAttribute().getName(), entityMetadata.getIdAttribute().getValue( source ) );

		entityDto.put( "stringRepresentation", domainTypeConfiguration.getEntityConfiguration().getNameExtractor().apply( source ) );

		for ( DomainTypeAttributeMetadata attribute : attributes ) {
			Object val;
			if ( null != ( val = attribute.getValue( source ) ) ) {
				entityDto.put( attribute.getName(), val );
			}
		}

		return new EntityResource( entityDto, links );
	}

//	private URI selfURI( Class<?> domainType, DomainTypeEntityMetadata entityMetadata, Object source ) {
//		Serializable id = ( Serializable ) entityMetadata.getIdAttribute().getValue( source );
//		return buildUri( baseUri, BeanNameGenerator.INSTANCE.repositoryServiceExporterName( domainType ), id.toString() );
//	}

	private Class attributeType( final Attribute attribute ) {
		return ( attribute instanceof PluralAttribute ? ( ( PluralAttribute ) attribute ).getElementType().getJavaType() : attribute.getJavaType() );
	}

	public void setJpaRepositoryExporter( final JpaRepositoryExporter jpaRepositoryExporter ) {
		this.jpaRepositoryExporter = jpaRepositoryExporter;
	}

	@Override
	@Autowired
	public void setGlobalAdministrationConfiguration( final GlobalAdministrationConfiguration configuration ) {
		this.configuration = configuration;
	}
}