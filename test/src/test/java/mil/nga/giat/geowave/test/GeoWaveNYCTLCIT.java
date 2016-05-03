package mil.nga.giat.geowave.test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.commons.math.util.MathUtils;
import org.apache.log4j.Logger;
import org.geotools.feature.AttributeTypeBuilder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.geometry.jts.GeometryBuilder;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

import mil.nga.giat.geowave.adapter.vector.FeatureDataAdapter;
import mil.nga.giat.geowave.adapter.vector.GeotoolsFeatureDataAdapter;
import mil.nga.giat.geowave.adapter.vector.export.VectorLocalExportCommand;
import mil.nga.giat.geowave.adapter.vector.export.VectorLocalExportOptions;
import mil.nga.giat.geowave.adapter.vector.stats.FeatureBoundingBoxStatistics;
import mil.nga.giat.geowave.adapter.vector.stats.FeatureNumericRangeStatistics;
import mil.nga.giat.geowave.adapter.vector.utils.TimeDescriptors;
import mil.nga.giat.geowave.core.cli.parser.ManualOperationParams;
import mil.nga.giat.geowave.core.geotime.GeometryUtils;
import mil.nga.giat.geowave.core.geotime.store.query.SpatialQuery;
import mil.nga.giat.geowave.core.geotime.store.statistics.BoundingBoxDataStatistics;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.ingest.GeoWaveData;
import mil.nga.giat.geowave.core.ingest.local.LocalFileIngestPlugin;
import mil.nga.giat.geowave.core.ingest.operations.LocalToGeowaveCommand;
import mil.nga.giat.geowave.core.ingest.operations.options.IngestFormatPluginOptions;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.DataStoreEntryInfo;
import mil.nga.giat.geowave.core.store.IndexWriter;
import mil.nga.giat.geowave.core.store.IngestCallback;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.adapter.DataAdapter;
import mil.nga.giat.geowave.core.store.adapter.WritableDataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.CountDataStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.core.store.adapter.statistics.RowRangeHistogramStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.StatisticsProvider;
import mil.nga.giat.geowave.core.store.data.visibility.GlobalVisibilityHandler;
import mil.nga.giat.geowave.core.store.data.visibility.UniformVisibilityWriter;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.memory.DataStoreUtils;
import mil.nga.giat.geowave.core.store.memory.MemoryAdapterStore;
import mil.nga.giat.geowave.core.store.operations.remote.options.DataStorePluginOptions;
import mil.nga.giat.geowave.core.store.operations.remote.options.IndexPluginOptions;
import mil.nga.giat.geowave.core.store.operations.remote.options.IndexPluginOptions.PartitionStrategy;
import mil.nga.giat.geowave.core.store.query.DataIdQuery;
import mil.nga.giat.geowave.core.store.query.DistributableQuery;
import mil.nga.giat.geowave.core.store.query.Query;
import mil.nga.giat.geowave.core.store.query.QueryOptions;
import mil.nga.giat.geowave.core.store.query.aggregate.CountAggregation;
import mil.nga.giat.geowave.core.store.query.aggregate.CountResult;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloDataStore;
import mil.nga.giat.geowave.datastore.accumulo.index.secondary.AccumuloSecondaryIndexDataStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloAdapterIndexMappingStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloAdapterStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloDataStatisticsStore;
import mil.nga.giat.geowave.datastore.accumulo.metadata.AccumuloIndexStore;
import mil.nga.giat.geowave.format.geotools.vector.GeoToolsVectorDataStoreIngestPlugin;
import mil.nga.giat.geowave.format.nyctlc.NYCTLCIngestPlugin;
import mil.nga.giat.geowave.format.nyctlc.NYCTLCUtils;
import mil.nga.giat.geowave.format.nyctlc.adapter.NYCTLCDataAdapter;
import mil.nga.giat.geowave.format.nyctlc.ingest.NYCTLCDimensionalityTypeProvider;
import mil.nga.giat.geowave.format.nyctlc.query.NYCTLCAggregation;
import mil.nga.giat.geowave.format.nyctlc.query.NYCTLCQuery;
import mil.nga.giat.geowave.format.nyctlc.statistics.NYCTLCStatistics;

public class GeoWaveNYCTLCIT extends
		GeoWaveTestEnvironment
{
	@Test
	public void testSingleThreadedIngestAndQuerySpatialPointsAndLines() throws ParseException, IOException {
		testIngestAndQuerySpatialPointsAndLines(1);
	}

	public void testIngestAndQuerySpatialPointsAndLines(
			final int nthreads ) throws ParseException, IOException {
		System.getProperties().put(
				"AccumuloIndexWriter.skipFlush",
				"true");

		// ingest a shapefile (geotools type) directly into GeoWave using the
		// ingest framework's main method and pre-defined commandline arguments

		// Ingest Formats
		IngestFormatPluginOptions ingestFormatOptions = new IngestFormatPluginOptions();
		ingestFormatOptions.selectPlugin("nyctlc");

		// Indexes
		IndexPluginOptions indexOption = new IndexPluginOptions();
//		indexOption.selectPlugin("spatial");
		indexOption.selectPlugin("nyctlc_sst");
		indexOption.numPartitions = 20;
		indexOption.partitionStrategy = PartitionStrategy.ROUND_ROBIN;
		// Create the command and execute.
		DataStorePluginOptions plugin = getAccumuloStorePluginOptions(TEST_NAMESPACE);
		LocalToGeowaveCommand localIngester = new LocalToGeowaveCommand();
		localIngester.setPluginFormats(ingestFormatOptions);
		localIngester.setInputIndexOptions(Arrays.asList(indexOption));
		localIngester.setInputStoreOptions(plugin);
		localIngester.setParameters(
				"green_tripdata_2013-08.csv",
				null,
				null);
		localIngester.setThreads(nthreads);
		localIngester.execute(new ManualOperationParams());

//		 QueryOptions queryOptions = new QueryOptions();
//
//		queryOptions.setIndex(new NYCTLCDimensionalityTypeProvider().createPrimaryIndex());
////		final Query query = new SpatialQuery(new WKTReader().read("POLYGON((-180 -90, 180 -90, 180 90, -180 90, -180 -90))"));
//		 Query query = new NYCTLCQuery(
//				0,
//				(int)TimeUnit.DAYS.toSeconds(1),
//				new WKTReader().read("POLYGON((-180 -90, 180 -90, 180 90, -180 90, -180 -90))"),
//				new WKTReader().read("POLYGON((-180 -90, 180 -90, 180 90, -180 90, -180 -90))"));
//
//		queryOptions.setAggregation(
//				new NYCTLCAggregation(),
//				new NYCTLCDataAdapter(NYCTLCUtils.createPointDataType()));
//
//		if (queryOptions.getAggregation() != null) {
//			final CloseableIterator<NYCTLCStatistics> results = plugin.createDataStore().query(
//					queryOptions,
//					query);
//
//			while (results.hasNext()) {
////				final CountResult stats = results.next();
////
////				System.out.println(stats.getCount());
//				final NYCTLCStatistics stats = results.next();
//
//				System.out.println(stats.toJSONObject().toString(
//						2));
//			}
//			results.close();
//		}
		
		QueryOptions queryOptions = new QueryOptions();

		queryOptions.setIndex((PrimaryIndex) plugin.createIndexStore().getIndices().next());
//		final Query query = new SpatialQuery(new WKTReader().read("POLYGON((-180 -90, 180 -90, 180 90, -180 90, -180 -90))"));

		GeometryBuilder bdr = new GeometryBuilder();
		Geometry startGeom = bdr.circle(-73.954818725585937, 40.820701599121094, 0.05, 20);
		
		bdr = new GeometryBuilder();
		Geometry destGeom =bdr.circle(-73.998832702636719, 40.729896545410156, 0.05, 20);
		NYCTLCQuery query = new NYCTLCQuery(
				1,50000,startGeom,destGeom
			);

//		SpatialQuery query = new SpatialQuery(destGeom);
		queryOptions.setAggregation(
				new NYCTLCAggregation(),
				new NYCTLCDataAdapter(NYCTLCUtils.createPointDataType()));

		if (queryOptions.getAggregation() != null) {
			final CloseableIterator<NYCTLCStatistics> results = plugin.createDataStore().query(
					queryOptions,
					query);

			while (results.hasNext()) {
//				final CountResult stats = results.next();
//
//				System.out.println(stats.getCount());
				final NYCTLCStatistics stats = results.next();

				System.out.println(stats.toJSONObject().toString(
						2));
			}
			results.close();
		}
	}
}