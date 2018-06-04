/*
 * MIT License
 *
 * Copyright 2017 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.broadinstitute.dropseqrna.utils.readiterators;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.*;
import org.broadinstitute.dropseqrna.barnyard.Utils;
import org.broadinstitute.dropseqrna.barnyard.digitalexpression.UMICollection;
import org.broadinstitute.dropseqrna.utils.*;
import picard.annotation.LocusFunction;

import java.util.Collection;

public class UMIIterator implements CloseableIterator<UMICollection>  {

    private static final Log log = Log.getInstance(UMIIterator.class);
    private static final ProgressLogger prog = new ProgressLogger(log);

	private final GroupingIterator<SAMRecord> atoi;
	private final String geneTag;
	private final String cellBarcodeTag;
	private final String molecularBarcodeTag;
    private final StringInterner stringCache = new StringInterner();

	/**
	 * Construct an object that generates UMI objects from a BAM file
     * @param headerAndIterator The BAM records to extract UMIs from
	 * @param geneTag The gene tag on BAM records
	 * @param cellBarcodeTag The cell barcode tag on BAM records
	 * @param molecularBarcodeTag The molecular barcode tag on BAM records
	 * @param strandTag The strand tag on BAM records
	 * @param readMQ The minimum map quality of the reads
	 * @param assignReadsToAllGenes Should records tagged with multiple genes be double counted, once for each gene?
	 * @param useStrandInfo should the gene and read strand match for the read to be accepted
	 * @param cellBarcodes The list of cell barcode tag values that match the <cellBarcodeTag> tag on the BAM records.
     *                     Only reads with these values will be used.  If set to null, all cell barcodes are used.
	 */
	public UMIIterator(final SamHeaderAndIterator headerAndIterator,
                       final String geneTag,
                       final String geneStrandTag,
                       final String geneFunctionTag,
                       final StrandStrategy strandStrategy,
                       final Collection <LocusFunction> acceptedLociFunctions,
                       final String cellBarcodeTag,
                       final String molecularBarcodeTag,
                       final int readMQ,
                       final boolean assignReadsToAllGenes,
                       final Collection<String> cellBarcodes) {
		this(headerAndIterator, geneTag, geneStrandTag, geneFunctionTag, strandStrategy, acceptedLociFunctions, cellBarcodeTag, molecularBarcodeTag, readMQ, assignReadsToAllGenes, cellBarcodes, false);
	}

	/**
	 * Construct an object that generates UMI objects from a BAM file
	 * @param headerAndIterator The BAM records to extract UMIs from
	 * @param geneTag The geneExon tag on BAM records
	 * @param cellBarcodeTag The cell barcode tag on BAM records
	 * @param molecularBarcodeTag The molecular barcode tag on BAM records
	 * @param strandTag The strand tag on BAM records
	 * @param readMQ The minimum map quality of the reads
	 * @param assignReadsToAllGenes Should records tagged with multiple genes be double counted, once for each gene?
	 * @param useStrandInfo should the gene and read strand match for the read to be accepted
	 * @param cellBarcodes The list of cell barcode tag values that match the <cellBarcodeTag> tag on the BAM records.
     *                     Only reads with these values will be used.  If set to null, all cell barcodes are used.
	 * @param cellFirstSort if true, then cell barcodes are sorted first, followed by gene/exon tags.
     *                      If false, then gene/exon tags are sorted first, followed by cells.  false is the default and used in the other constructor.
	 */
	public UMIIterator(final SamHeaderAndIterator headerAndIterator,
					   final String geneTag,
                       final String geneStrandTag,
                       final String geneFunctionTag,
                       final StrandStrategy strandStrategy,
                       final Collection <LocusFunction> acceptedLociFunctions,
                       final String cellBarcodeTag,
                       final String molecularBarcodeTag,
                       final int readMQ,
                       final boolean assignReadsToAllGenes,
                       final Collection<String> cellBarcodes,
                       final boolean cellFirstSort) {

        this.geneTag=geneTag;
        this.cellBarcodeTag=cellBarcodeTag;
        this.molecularBarcodeTag=molecularBarcodeTag;

        final StringTagComparator cellBarcodeTagComparator = new StringTagComparator(cellBarcodeTag);
        final StringTagComparator geneExonTagComparator = new StringTagComparator(geneTag);
        final MultiComparator<SAMRecord> multiComparator;
		if (cellFirstSort)
			multiComparator = new MultiComparator<>(cellBarcodeTagComparator, geneExonTagComparator);
		else
			multiComparator = new MultiComparator<>(geneExonTagComparator, cellBarcodeTagComparator);
        // Filter records before sorting, to reduce I/O
		MissingTagFilteringIterator filteringIterator =
                new MissingTagFilteringIterator(headerAndIterator.iterator, cellBarcodeTag, geneTag, molecularBarcodeTag);

		// Filter reads on map quality
		MapQualityFilteredIterator filteringIterator2 = new MapQualityFilteredIterator(filteringIterator, readMQ, true);

		// Filter reads on if the read contains a cell barcode, if cell barcodes have been specified.
		FilteredIterator<SAMRecord> filteringIterator3 =
				(cellBarcodes != null?
						new TagValueFilteringIterator<String>(filteringIterator2, this.cellBarcodeTag, cellBarcodes):
				filteringIterator2);

		// Filter/assign reads based on functional annotations
		GeneFunctionIteratorWrapper gfteratorWrapper = new GeneFunctionIteratorWrapper(filteringIterator3, geneTag, geneStrandTag, geneFunctionTag, assignReadsToAllGenes, strandStrategy, acceptedLociFunctions);


		// assign the tags in the order you want data sorted.
		// UmiIteratorWrapper umiIteratorWrapper = new UmiIteratorWrapper(filteringIterator2.iterator(), cellBarcodeTag,
        //         cellBarcodes, geneTag, strandTag, readMQ, assignReadsToAllGenes, useStrandInfo);

        CloseableIterator<SAMRecord> sortedAlignmentIterator = SamRecordSortingIteratorFactory.create(
                headerAndIterator.header, gfteratorWrapper, multiComparator, prog);

        // Not really -- merge sort is ongoing.
        log.info("Sorting finished.");

		this.atoi = new GroupingIterator<>(sortedAlignmentIterator, multiComparator);
	}

    /**
	 * Gets the next UMI Collection - all the molecular barcodes and reads on those for a particular gene/cell.
	 * @return Null if there are no reads left in the iterator.  Otherwise, returns a UMICollection.
	 */
	@Override
	public UMICollection next () {
		if (!this.atoi.hasNext())
			return null;

		Collection<SAMRecord> records = this.atoi.next();
		PeekableIterator<SAMRecord> recordCollectionIter = new PeekableIterator<>(records.iterator());

		// the next record is the first of the "batch"
		SAMRecord r = recordCollectionIter.peek();
		String currentGene = r.getStringAttribute(geneTag);
		String currentCell = Utils.getCellBC(r, cellBarcodeTag);
		UMICollection umi = new UMICollection(currentCell, currentGene);

		while (recordCollectionIter.hasNext()) {
			// if there's a next read, set up the current gene/cell variables.
			r=recordCollectionIter.next();
            // Cache the UMI.  Note that within a single (cell, gene), the ObjectCounter effectively caches the UMI string,
            // but they are not cached *across* (cell, gene), so this should help.
			String molecularBarcode = stringCache.intern(r.getStringAttribute(this.molecularBarcodeTag));
			umi.incrementMolecularBarcodeCount(molecularBarcode);

		}
		// I ran out of reads
		recordCollectionIter.close();
		return (umi);
	}

	@Override
	public void remove() {
		this.atoi.remove();
	}

	@Override
	public void close() {
        CloserUtil.close(this.atoi);
	}

	@Override
	public boolean hasNext() {
		return this.atoi.hasNext();
	}


}
