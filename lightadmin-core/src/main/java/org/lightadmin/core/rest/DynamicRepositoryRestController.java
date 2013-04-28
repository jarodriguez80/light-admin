package org.lightadmin.core.rest;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.lightadmin.core.config.bootstrap.parsing.configuration.DomainConfigurationUnitType;
import org.lightadmin.core.config.domain.DomainTypeAdministrationConfiguration;
import org.lightadmin.core.config.domain.DomainTypeBasicConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfigurationAware;
import org.lightadmin.core.config.domain.field.FieldMetadata;
import org.lightadmin.core.config.domain.scope.ScopeMetadata;
import org.lightadmin.core.config.domain.scope.ScopeMetadataUtils;
import org.lightadmin.core.persistence.metamodel.DomainTypeAttributeMetadata;
import org.lightadmin.core.persistence.metamodel.DomainTypeEntityMetadata;
import org.lightadmin.core.persistence.repository.DynamicJpaRepository;
import org.lightadmin.core.search.SpecificationCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.rest.repository.AttributeMetadata;
import org.springframework.data.rest.repository.RepositoryConstraintViolationException;
import org.springframework.data.rest.repository.RepositoryMetadata;
import org.springframework.data.rest.webmvc.PagingAndSorting;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedResources;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.lightadmin.core.config.domain.scope.ScopeMetadataUtils.isPredicateScope;
import static org.lightadmin.core.config.domain.scope.ScopeMetadataUtils.isSpecificationScope;

@SuppressWarnings( "unchecked" )
@RequestMapping( "/rest" )
public class DynamicRepositoryRestController extends FlexibleRepositoryRestController implements GlobalAdministrationConfigurationAware {

	private SpecificationCreator specificationCreator;

	private GlobalAdministrationConfiguration configuration;

	private ApplicationContext applicationContext;

	@PostConstruct
	public void init() throws Exception {
		specificationCreator = new SpecificationCreator( conversionService, configuration );
	}

	@RequestMapping( value = "/{repository}", method = RequestMethod.PUT )
	@ResponseBody
	public ResponseEntity<?> createOrUpdate( ServletServerHttpRequest request, URI baseUri, @PathVariable String repository ) throws IOException, IllegalAccessException, InstantiationException {
		return super.createOrUpdate( request, baseUri, repository, "" );
	}

	private static final Date NULL_PLACEHOLDER_MAGIC_DATE = new Date( -377743392000001L );

	@Override
	@SuppressWarnings( "rawtypes" )
	protected void attrMetaSet( AttributeMetadata attrMeta, Object incomingVal, Object entity ) {
		DomainTypeBasicConfiguration repo;
		if ( attrMeta.isCollectionLike() || attrMeta.isSetLike() ) {
			// Trying to avoid collection-was-no-longer-referenced issue
			// if the collection is modifiable
			try {
				Collection col = ( Collection ) attrMeta.get( entity );
				col.clear();
				col.addAll( ( Collection ) incomingVal );
			} catch ( UnsupportedOperationException e ) {
				attrMeta.set( incomingVal, entity );
			}
		} else if ( ( repo = configuration.forDomainType( attrMeta.type() ) ) != null && ( repo.getRepository().isNullPlaceholder( incomingVal ) ) ) {
			attrMeta.set( null, entity );
		} else if ( NULL_PLACEHOLDER_MAGIC_DATE.equals( incomingVal ) ) {
			attrMeta.set( null, entity );
		} else {
			attrMeta.set( incomingVal, entity );
		}
	}

	@RequestMapping( value = "/{repository}/{id}/{property}/file", method = RequestMethod.DELETE )
	@ResponseBody
	public ResponseEntity<?> deleteFileOfPropertyOfEntity( ServletServerHttpRequest request, URI baseUri, @PathVariable String repository, @PathVariable String id, @PathVariable String property ) throws IOException {
		final RepositoryMetadata repoMeta = repositoryMetadataFor( repository );
		final Serializable serId = stringToSerializable( id, ( Class<? extends Serializable> ) repoMeta.entityMetadata().idAttribute().type() );
		final CrudRepository repo = repoMeta.repository();

		final Object entity;
		final AttributeMetadata attrMeta;
		if ( null == ( entity = repo.findOne( serId ) ) || null == ( attrMeta = repoMeta.entityMetadata().attribute( property ) ) ) {
			return notFoundResponse( request );
		}

		attrMeta.set( null, entity );

		repo.save( entity );

		return new ResponseEntity( new HttpHeaders(), HttpStatus.OK );
	}

	@RequestMapping( value = "/{repository}/{id}/{property}/file", method = RequestMethod.GET )
	@ResponseBody
	public ResponseEntity<?> filePropertyOfEntity( ServletServerHttpRequest request, HttpServletResponse response, URI baseUri, @PathVariable String repository, @PathVariable String id, @PathVariable String property, @RequestParam( value = "width", defaultValue = "-1" ) int width, @RequestParam( value = "height", defaultValue = "-1" ) int height ) throws IOException {
		final RepositoryMetadata repoMeta = repositoryMetadataFor( repository );
		final Serializable serId = stringToSerializable( id, ( Class<? extends Serializable> ) repoMeta.entityMetadata().idAttribute().type() );

		final Object entity;
		final AttributeMetadata attrMeta;
		if ( null == ( entity = repoMeta.repository().findOne( serId ) ) || null == ( attrMeta = repoMeta.entityMetadata().attribute( property ) ) ) {
			return notFoundResponse( request );
		}

		if ( attrMeta.type().equals( byte[].class ) ) {
			final byte[] bytes = ( byte[] ) attrMeta.get( entity );
			if ( bytes != null ) {
				BufferedImage image = resizeImage( bytes, width, height );
				ImageIO.write( image, "jpeg", response.getOutputStream() );
				response.flushBuffer();

				HttpHeaders responseHeaders = new HttpHeaders();
				responseHeaders.setContentLength( bytes.length );
				responseHeaders.setContentType( MediaType.IMAGE_JPEG );

				return new ResponseEntity( responseHeaders, HttpStatus.OK );
			}
		}
		return new ResponseEntity( HttpStatus.BAD_REQUEST );
	}

	private BufferedImage resizeImage( byte[] content, int width, int height ) throws IOException {
		BufferedImage sourceImage = ImageIO.read( new ByteArrayInputStream( content ) );
		if ( width < 0 && height < 0 ) {
			return sourceImage;
		}

		Image thumbnail = sourceImage.getScaledInstance( width, height, Image.SCALE_SMOOTH );

		BufferedImage resizedImage = new BufferedImage( thumbnail.getWidth( null ), thumbnail.getHeight( null ), sourceImage.getType() );

		resizedImage.getGraphics().drawImage( thumbnail, 0, 0, null );

		return resizedImage;
	}


	@RequestMapping( value = "/upload", method = {RequestMethod.PUT, RequestMethod.POST} )
	@ResponseBody
	public ResponseEntity<?> saveFilePropertyOfEntity( final ServletServerHttpRequest request ) throws IOException {
		final MultipartHttpServletRequest multipartHttpServletRequest = ( MultipartHttpServletRequest ) request.getServletRequest();

		final Map<String, MultipartFile> fileMap = multipartHttpServletRequest.getFileMap();

		if ( !fileMap.isEmpty() ) {
			final Map.Entry<String, MultipartFile> fileEntry = fileMap.entrySet().iterator().next();

			final Map<String, Object> result = newLinkedHashMap();
			result.put( "fileName", fileEntry.getValue().getOriginalFilename() );
			result.put( "fileContent", fileEntry.getValue().getBytes() );

			return negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), result );
		}

		return new ResponseEntity( HttpStatus.METHOD_NOT_ALLOWED );
	}

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/{id}/unit/{configurationUnit}", method = RequestMethod.GET )
	public ResponseEntity<?> entity( ServletServerHttpRequest request, URI baseUri, @PathVariable String repositoryName, @PathVariable String id, @PathVariable String configurationUnit ) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		Serializable entityId = stringToSerializable( id, ( Class<? extends Serializable> ) domainTypeEntityMetadata.getIdAttribute().getType() );

		final Object entity = repository.findOne( entityId );

		return negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), new DomainTypeResource( entity, fields( configurationUnit, domainTypeAdministrationConfiguration ) ) );
	}

	// TODO: Draft impl.
	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/scope/{scopeName}/search/count", method = RequestMethod.GET, produces = "application/json" )
	public ResponseEntity<?> countItems( ServletServerHttpRequest request, @SuppressWarnings( "unused" ) URI baseUri, @PathVariable String repositoryName, @PathVariable String scopeName ) {
		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		final ScopeMetadata scope = domainTypeAdministrationConfiguration.getScopes().getScope( scopeName );

		final Specification filterSpecification = specificationFromRequest( request, domainTypeEntityMetadata );

		if ( isPredicateScope( scope ) ) {
			final ScopeMetadataUtils.PredicateScopeMetadata predicateScope = ( ScopeMetadataUtils.PredicateScopeMetadata ) scope;

			return responseEntity( countItemsBySpecificationAndPredicate( repository, filterSpecification, predicateScope.predicate() ) );
		}

		if ( isSpecificationScope( scope ) ) {
			final Specification scopeSpecification = ( ( ScopeMetadataUtils.SpecificationScopeMetadata ) scope ).specification();

			return responseEntity( countItemsBySpecification( repository, and( scopeSpecification, filterSpecification ) ) );
		}

		return responseEntity( countItemsBySpecification( repository, filterSpecification ) );
	}

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/scope/{scopeName}/search", method = RequestMethod.GET )
	public ResponseEntity<?> filterEntities( ServletServerHttpRequest request, @SuppressWarnings( "unused" ) URI baseUri, PagingAndSorting pageSort, @PathVariable String repositoryName, @PathVariable String scopeName ) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		final ScopeMetadata scope = domainTypeAdministrationConfiguration.getScopes().getScope( scopeName );

		final Specification filterSpecification = specificationFromRequest( request, domainTypeEntityMetadata );

		Set<FieldMetadata> listViewFields = domainTypeAdministrationConfiguration.getListViewFragment().getFields();

		if ( isPredicateScope( scope ) ) {
			final ScopeMetadataUtils.PredicateScopeMetadata predicateScope = ( ScopeMetadataUtils.PredicateScopeMetadata ) scope;

			final Page page = findBySpecificationAndPredicate( repository, filterSpecification, predicateScope.predicate(), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
		}

		if ( isSpecificationScope( scope ) ) {
			final Specification scopeSpecification = ( ( ScopeMetadataUtils.SpecificationScopeMetadata ) scope ).specification();

			Page page = findItemsBySpecification( repository, and( scopeSpecification, filterSpecification ), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
		}

		Page page = findItemsBySpecification( repository, filterSpecification, pageSort );

		return negotiateResponse( request, page, pageMetadata( page ), listViewFields );
	}

	@Override
	@ExceptionHandler( RepositoryConstraintViolationException.class )
	@ResponseBody
	public ResponseEntity handleValidationFailure( RepositoryConstraintViolationException ex, ServletServerHttpRequest request ) throws IOException {
		final Map packet = newHashMap();
		final List<Map<String, String>> errors = newArrayList();

		for ( FieldError fe : ex.getErrors().getFieldErrors() ) {
			List<Object> args = newArrayList( fe.getObjectName(), fe.getField(), fe.getRejectedValue() );

			if ( fe.getArguments() != null ) {
				Collections.addAll( args, fe.getArguments() );
			}

			String msg = applicationContext.getMessage( fe.getCode(), args.toArray(), fe.getDefaultMessage(), null );
			Map<String, String> error = newHashMap();
			error.put( "field", fe.getField() );
			error.put( "message", msg );
			errors.add( error );
		}
		packet.put( "errors", errors );

		return negotiateResponse( request, HttpStatus.BAD_REQUEST, new HttpHeaders(), packet );
	}

	@Override
	@ExceptionHandler( Exception.class )
	@ResponseBody
	public ResponseEntity handleMiscFailures( Throwable t, ServletServerHttpRequest request ) throws IOException {
		LOG.debug( "Handled exception", t );
		Map<String, String> error = singletonMap( "message", t.getLocalizedMessage() );
		Map packet = newHashMap();
		packet.put( "errors", asList( error ) );
		return negotiateResponse( request, HttpStatus.BAD_REQUEST, new HttpHeaders(), packet );
	}

	@Override
	@ExceptionHandler( {HttpMessageNotReadableException.class, HttpMessageNotWritableException.class} )
	@ResponseBody
	public ResponseEntity handleMessageConversionFailure( Exception ex, HttpServletRequest request ) throws IOException {
		LOG.error( "Handled exception", ex );
		return handleMiscFailures( ex.getCause(), new ServletServerHttpRequest( request ) );
	}

	private Set<FieldMetadata> fields( String configurationUnit, DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration ) {
		final DomainConfigurationUnitType configurationUnitType = DomainConfigurationUnitType.forName( configurationUnit );

		switch ( configurationUnitType ) {
			case SHOW_VIEW:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
			case FORM_VIEW:
				return domainTypeAdministrationConfiguration.getFormViewFragment().getFields();
			case QUICK_VIEW:
				return domainTypeAdministrationConfiguration.getQuickViewFragment().getFields();
			default:
				return domainTypeAdministrationConfiguration.getShowViewFragment().getFields();
		}
	}

	private long countItemsBySpecificationAndPredicate( DynamicJpaRepository repository, final Specification specification, Predicate predicate ) {
		final List<?> items = findItemsBySpecification( repository, specification );

		return Collections2.filter( items, predicate ).size();
	}

	private long countItemsBySpecification( final DynamicJpaRepository repository, final Specification specification ) {
		return repository.count( specification );
	}

	private Page findBySpecificationAndPredicate( DynamicJpaRepository repository, final Specification specification, Predicate predicate, final PagingAndSorting pageSort ) {
		final List<?> items = findItemsBySpecification( repository, specification, pageSort.getSort() );

		return selectPage( newArrayList( Collections2.filter( items, predicate ) ), pageSort );
	}

	private Page<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final PagingAndSorting pageSort ) {
		return repository.findAll( specification, pageSort );
	}

	private List<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final Sort sort ) {
		return repository.findAll( specification, sort );
	}

	private List<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification ) {
		return repository.findAll( specification );
	}

	private Page<?> selectPage( List<Object> items, PagingAndSorting pageSort ) {
		final List<Object> itemsOnPage = items.subList( pageSort.getOffset(), Math.min( items.size(), pageSort.getOffset() + pageSort.getPageSize() ) );

		return new PageImpl<Object>( itemsOnPage, pageSort, items.size() );
	}

	private Specification and( Specification specification, Specification otherSpecification ) {
		return Specifications.where( specification ).and( otherSpecification );
	}

	private Specification specificationFromRequest( ServletServerHttpRequest request, final DomainTypeEntityMetadata<? extends DomainTypeAttributeMetadata> entityMetadata ) {
		final Map<String, String[]> parameters = request.getServletRequest().getParameterMap();

		return specificationCreator.toSpecification( entityMetadata, parameters );
	}

	private ResponseEntity<?> negotiateResponse( ServletServerHttpRequest request, Page page, PagedResources.PageMetadata pageMetadata, Set<FieldMetadata> fieldMetadatas ) throws IOException {
		return negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), new PagedResources( toResources( page, fieldMetadatas ), pageMetadata, Lists.<Link>newArrayList() ) );
	}

	private PagedResources.PageMetadata pageMetadata( final Page page ) {
		return new PagedResources.PageMetadata( page.getSize(), page.getNumber() + 1, page.getTotalElements(), page.getTotalPages() );
	}

	private List<Object> toResources( Page page, Set<FieldMetadata> fieldMetadatas ) {
		if ( !page.hasContent() ) {
			return newLinkedList();
		}

		List<Object> allResources = newArrayList();
		for ( final Object item : page ) {
			allResources.add( new DomainTypeResource( item, fieldMetadatas ) );
		}
		return allResources;
	}

	private ResponseEntity<String> responseEntity( Object value ) {
		return new ResponseEntity<String>( String.valueOf( value ), new HttpHeaders(), HttpStatus.OK );
	}

	@Override
	@Autowired
	public void setGlobalAdministrationConfiguration( final GlobalAdministrationConfiguration configuration ) {
		this.configuration = configuration;
	}

	@Override
	public void setApplicationContext( ApplicationContext applicationContext ) throws BeansException {
		super.setApplicationContext( applicationContext );
		this.applicationContext = applicationContext;
	}
}
