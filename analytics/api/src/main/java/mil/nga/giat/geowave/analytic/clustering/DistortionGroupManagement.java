package mil.nga.giat.geowave.analytic.clustering;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mil.nga.giat.geowave.analytic.AnalyticItemWrapperFactory;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.StringUtils;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.DataStore;
import mil.nga.giat.geowave.core.store.adapter.AdapterPersistenceEncoding;
import mil.nga.giat.geowave.core.store.adapter.AdapterStore;
import mil.nga.giat.geowave.core.store.adapter.IndexedAdapterPersistenceEncoding;
import mil.nga.giat.geowave.core.store.adapter.WritableDataAdapter;
import mil.nga.giat.geowave.core.store.data.PersistentDataset;
import mil.nga.giat.geowave.core.store.data.field.FieldReader;
import mil.nga.giat.geowave.core.store.data.field.FieldUtils;
import mil.nga.giat.geowave.core.store.data.field.FieldVisibilityHandler;
import mil.nga.giat.geowave.core.store.data.field.FieldWriter;
import mil.nga.giat.geowave.core.store.index.CommonIndexModel;
import mil.nga.giat.geowave.core.store.index.CommonIndexValue;
import mil.nga.giat.geowave.core.store.index.Index;
import mil.nga.giat.geowave.core.store.index.IndexStore;
import mil.nga.giat.geowave.core.store.index.NullIndex;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.io.Writable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Find the max change in distortion between some k and k-1, picking the value k
 * associated with that change.
 * 
 * In a multi-group setting, each group may have a different optimal k. Thus,
 * the optimal batch may be different for each group. Each batch is associated
 * with a different value k.
 * 
 * Choose the appropriate batch for each group. Then change the batch identifier
 * for group centroids to a final provided single batch identifier ( parent
 * batch ).
 * 
 */
public class DistortionGroupManagement
{

	final static Logger LOGGER = LoggerFactory.getLogger(DistortionGroupManagement.class);
	public final static Index DISTORTIONS_INDEX = new NullIndex(
			"DISTORTIONS");

	/**
	 * 
	 * @param ops
	 * @param distortationTableName
	 *            the name of the table holding the distortions
	 * @param parentBatchId
	 *            the batch id to associate with the centroids for each group
	 * @return
	 */
	public static <T> int retainBestGroups(
			final DataStore dataStore,
			final IndexStore indexStore,
			final AdapterStore adapterStore,
			final AnalyticItemWrapperFactory<T> itemWrapperFactory,
			final String dataTypeId,
			final String indexId,
			final String parentBatchId,
			final int level ) {

		try {
			final Map<String, DistortionGroup> groupDistortions = new HashMap<String, DistortionGroup>();

			// row id is group id
			// colQual is cluster count
			try (CloseableIterator<DistortionEntry> it = dataStore.query(
					new DistortionDataAdapter(),
					DISTORTIONS_INDEX,
					null)) {
				while (it.hasNext()) {
					final DistortionEntry entry = it.next();
					final String groupID = entry.getGroupId();
					final Integer clusterCount = entry.getClusterCount();
					final Double distortion = entry.getDistortionValue();

					DistortionGroup grp = groupDistortions.get(groupID);
					if (grp == null) {
						grp = new DistortionGroup(
								groupID);
						groupDistortions.put(
								groupID,
								grp);
					}
					grp.addPair(
							clusterCount,
							distortion);
				}
			}

			final CentroidManagerGeoWave<T> centroidManager = new CentroidManagerGeoWave<T>(
					dataStore,
					indexStore,
					adapterStore,
					itemWrapperFactory,
					dataTypeId,
					indexId,
					parentBatchId,
					level);

			for (final DistortionGroup grp : groupDistortions.values()) {
				final int optimalK = grp.bestCount();
				LOGGER.info("Batch: " + parentBatchId + "; Group: " + grp.groupID + "; Optimal Cluster Size: " + optimalK);
				final String batchId = parentBatchId + "_" + optimalK;
				centroidManager.transferBatch(
						batchId,
						grp.getGroupID());
			}
		}
		catch (final RuntimeException ex) {
			throw ex;
		}
		catch (final Exception ex) {
			LOGGER.error(
					"Cannot detremine groups for batch" + parentBatchId,
					ex);
			return 1;
		}
		return 0;
	}

	public static class DistortionEntry implements
			Writable
	{
		private String groupId;
		private Integer clusterCount;
		private Double distortionValue;

		public DistortionEntry() {}

		public DistortionEntry(
				final String groupId,
				final Integer clusterCount,
				final Double distortionValue ) {
			this.groupId = groupId;
			this.clusterCount = clusterCount;
			this.distortionValue = distortionValue;
		}

		private DistortionEntry(
				final ByteArrayId dataId,
				final Double distortionValue ) {
			final String dataIdStr = StringUtils.stringFromBinary(dataId.getBytes());
			final String[] split = dataIdStr.split("/");
			groupId = split[0];
			clusterCount = Integer.parseInt(split[1]);
			this.distortionValue = distortionValue;
		}

		public String getGroupId() {
			return groupId;
		}

		public Integer getClusterCount() {
			return clusterCount;
		}

		public Double getDistortionValue() {
			return distortionValue;
		}

		private ByteArrayId getDataId() {
			return new ByteArrayId(
					groupId + "/" + clusterCount);
		}

		@Override
		public void write(
				final DataOutput out )
				throws IOException {
			out.writeUTF(groupId);
			out.writeInt(clusterCount);
			out.writeDouble(distortionValue);
		}

		@Override
		public void readFields(
				final DataInput in )
				throws IOException {
			groupId = in.readUTF();
			clusterCount = in.readInt();
			distortionValue = in.readDouble();
		}
	}

	private static class DistortionGroup
	{
		final String groupID;
		final List<Pair<Integer, Double>> clusterCountToDistortion = new ArrayList<Pair<Integer, Double>>();

		public DistortionGroup(
				final String groupID ) {
			this.groupID = groupID;
		}

		public void addPair(
				final Integer count,
				final Double distortion ) {
			clusterCountToDistortion.add(Pair.of(
					count,
					distortion));
		}

		public String getGroupID() {
			return groupID;
		}

		public int bestCount() {
			Collections.sort(
					clusterCountToDistortion,
					new Comparator<Pair<Integer, Double>>() {

						@Override
						public int compare(
								final Pair<Integer, Double> arg0,
								final Pair<Integer, Double> arg1 ) {
							return arg0.getKey().compareTo(
									arg1.getKey());
						}
					});
			double maxJump = -1.0;
			Integer jumpIdx = -1;
			Double oldD = 0.0; // base case !?
			for (final Pair<Integer, Double> pair : clusterCountToDistortion) {
				final Double jump = pair.getValue() - oldD;
				if (jump > maxJump) {
					maxJump = jump;
					jumpIdx = pair.getKey();
				}
				oldD = pair.getValue();
			}
			return jumpIdx;
		}
	}

	public static class DistortionDataAdapter implements
			WritableDataAdapter<DistortionEntry>
	{
		public final static ByteArrayId ADAPTER_ID = new ByteArrayId(
				"distortion");
		private final static ByteArrayId DISTORTION_FIELD_ID = new ByteArrayId(
				"distortion");
		private final FieldVisibilityHandler<DistortionEntry, Object> distortionVisibilityHandler;

		public DistortionDataAdapter() {
			this(
					null);
		}

		public DistortionDataAdapter(
				final FieldVisibilityHandler<DistortionEntry, Object> distortionVisibilityHandler ) {
			this.distortionVisibilityHandler = distortionVisibilityHandler;
		}

		@Override
		public ByteArrayId getAdapterId() {
			return ADAPTER_ID;
		}

		@Override
		public boolean isSupported(
				final DistortionEntry entry ) {
			return true;
		}

		@Override
		public ByteArrayId getDataId(
				final DistortionEntry entry ) {
			return entry.getDataId();
		}

		@Override
		public DistortionEntry decode(
				final IndexedAdapterPersistenceEncoding data,
				final Index index ) {
			return new DistortionEntry(
					data.getDataId(),
					(Double) data.getAdapterExtendedData().getValue(
							DISTORTION_FIELD_ID));
		}

		@Override
		public AdapterPersistenceEncoding encode(
				final DistortionEntry entry,
				final CommonIndexModel indexModel ) {
			final Map<ByteArrayId, Object> fieldIdToValueMap = new HashMap<ByteArrayId, Object>();
			fieldIdToValueMap.put(
					DISTORTION_FIELD_ID,
					entry.getDistortionValue());
			return new AdapterPersistenceEncoding(
					getAdapterId(),
					entry.getDataId(),
					new PersistentDataset<CommonIndexValue>(),
					new PersistentDataset<Object>(
							fieldIdToValueMap));
		}

		@Override
		public FieldReader<Object> getReader(
				final ByteArrayId fieldId ) {
			if (DISTORTION_FIELD_ID.equals(fieldId)) {
				return (FieldReader) FieldUtils.getDefaultReaderForClass(Double.class);
			}
			return null;
		}

		@Override
		public byte[] toBinary() {
			return new byte[] {};
		}

		@Override
		public void fromBinary(
				final byte[] bytes ) {}

		@Override
		public FieldWriter<DistortionEntry, Object> getWriter(
				final ByteArrayId fieldId ) {
			if (DISTORTION_FIELD_ID.equals(fieldId)) {
				if (distortionVisibilityHandler != null) {
					return (FieldWriter) FieldUtils.getDefaultWriterForClass(
							Double.class,
							distortionVisibilityHandler);
				}
				else {
					return (FieldWriter) FieldUtils.getDefaultWriterForClass(Double.class);
				}
			}
			return null;
		}

	}

}
