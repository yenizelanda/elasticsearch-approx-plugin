package com.pearson.entech.elasticsearch.search.facet.approx.termlist;

import static com.google.common.collect.Sets.newHashSet;

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.trove.set.TIntSet;
import org.elasticsearch.common.trove.set.TLongSet;
import org.elasticsearch.common.trove.set.hash.TIntHashSet;
import org.elasticsearch.common.trove.set.hash.TLongHashSet;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.data.FieldData.StringValueProc;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.field.data.FieldDataType.DefaultTypes;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.FacetPhaseExecutionException;
import org.elasticsearch.search.internal.SearchContext;

/**
 * The Class TermListFacetCollector.
 */
public class TermListFacetCollector extends AbstractFacetCollector {

    private final boolean _readFromFieldCache;

    private final String _facetName;

    private final int _maxPerShard;

    private final String _keyFieldName;

    private final FieldDataType _keyFieldType;

    private final FieldDataCache _fieldDataCache;

    private FieldData _keyFieldData;

    private Collection<String> _strings;

    private TIntSet _ints;

    private TLongSet _longs;

    private final StringValueProc _proc = new KeyFieldVisitor();

    /**
     * Instantiates a new term list facet collector.
     *
     * @param facetName the facet name
     * @param keyField the key field
     * @param context the ES search context
     * @param maxPerShard max terms to retrieve per shard
     * @param readFromCache if true, read from ES field data cache; otherwise read from Lucene index
     */
    public TermListFacetCollector(final String facetName, final String keyField,
            final SearchContext context, final int maxPerShard, final boolean readFromCache) {
        super(facetName);
        _facetName = facetName;
        _maxPerShard = maxPerShard;
        _readFromFieldCache = readFromCache;

        _fieldDataCache = context.fieldDataCache();
        final MapperService.SmartNameFieldMappers keyMappers = context.smartFieldMappers(keyField);

        if(keyMappers == null || !keyMappers.hasMapper()) {
            throw new FacetPhaseExecutionException(facetName, "No mapping found for key field [" + keyField + "]");
        }

        // add type filter if there is exact doc mapper associated with it
        if(keyMappers.explicitTypeInNameWithDocMapper()) {
            setFilter(context.filterCache().cache(keyMappers.docMapper().typeFilter()));
        }

        final FieldMapper keyMapper = keyMappers.mapper();
        _keyFieldName = keyMapper.names().indexName();
        _keyFieldType = keyMapper.fieldDataType();
        if(_keyFieldType.equals(DefaultTypes.STRING))
            _strings = newHashSet();
        else if(_keyFieldType.equals(DefaultTypes.INT))
            _ints = new TIntHashSet();
        else if(_keyFieldType.equals(DefaultTypes.LONG))
            _longs = new TLongHashSet();
    }

    // TODO make this work for other data types too

    /* (non-Javadoc)
     * @see org.elasticsearch.search.facet.FacetCollector#facet()
     */
    @Override
    public Facet facet() {
        if(_strings != null)
            return new InternalTermListFacet(_facetName, _strings.toArray());
        else if(_ints != null)
            return new InternalTermListFacet(_facetName, _ints.toArray());
        else if(_longs != null)
            return new InternalTermListFacet(_facetName, _longs.toArray());
        else
            return new InternalTermListFacet(_facetName);
    }

    /**
     * This method gets called once for each index segment, with a new reader.
     *
     * @param reader the reader
     * @param docBase the docBase index (ignored)
     * @throws IOException Signals that an I/O exception has occurred.
     */
    @Override
    protected void doSetNextReader(final IndexReader reader, final int docBase) throws IOException {
        if(_readFromFieldCache) {
            _keyFieldData = _fieldDataCache.cache(_keyFieldType, reader, _keyFieldName);
        } else {

            // retrieve terms directly from the lucene index.
            final TermEnum terms = reader.terms();
            while(terms.next()) {
                final Term term = terms.term();
                if(_keyFieldName.equals(term.field())) {

                    //since saveValue is used both for cached and nonCached queries,
                    //ensure that it receives the proper string values

                    //this is suboptimal, because we are casting numeric values to strings to be used in saveValue
                    //and then, in saveValue we are again converting strings to numbers
                    if(_strings != null && _strings.size() <= _maxPerShard)
                        saveValue(term.text());
                    else if(_ints != null && _ints.size() <= _maxPerShard && term.text().length() == NumericUtils.BUF_SIZE_INT) {
                        final Integer val = NumericUtils.prefixCodedToInt(term.text());
                        saveValue(val.toString());
                    }
                    else if(_longs != null && _longs.size() <= _maxPerShard && term.text().length() == NumericUtils.BUF_SIZE_LONG) {
                        final Long val = NumericUtils.prefixCodedToLong(term.text());
                        saveValue(val.toString());
                    }

                }
            }

        }
    }

    /* (non-Javadoc)
     * @see org.elasticsearch.search.facet.AbstractFacetCollector#doCollect(int)
     */
    @Override
    protected void doCollect(final int doc) throws IOException {
        if(_readFromFieldCache)
            _keyFieldData.forEachValue(_proc);
        // Otherwise do nothing -- we just read the values from the index directly
    }

    /**
     *  For each term in the visitor, save the value in the appropriate array given the field datatype
     *
     * @param value the value
     */

    private void saveValue(final String value) {
        if(_strings != null && _strings.size() <= _maxPerShard) {
            _strings.add(value);
        } else if(_ints != null && _ints.size() <= _maxPerShard) {
            try {
                _ints.add(Integer.valueOf(value));
            } catch(final NumberFormatException ex) {
                //ignore exceptions
            }
        }
        else if(_longs != null && _longs.size() <= _maxPerShard) {
            try {
                _longs.add(Long.valueOf(value));
            } catch(final NumberFormatException ex) {
                //ignore exceptions
            }
        }
    }

    /**
     * The Class KeyFieldVisitor.
     */
    private class KeyFieldVisitor implements StringValueProc {

        /* (non-Javadoc)
         * @see org.elasticsearch.index.field.data.FieldData.StringValueProc#onValue(java.lang.String)
         */
        @Override
        public void onValue(final String value) {
            saveValue(value);
        }
    }

}
