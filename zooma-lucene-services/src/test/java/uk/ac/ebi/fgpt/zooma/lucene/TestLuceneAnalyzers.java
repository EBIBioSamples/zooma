package uk.ac.ebi.fgpt.zooma.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Javadocs go here!
 *
 * @author Tony Burdett
 * @date 31/08/12
 */
public class TestLuceneAnalyzers {

    private IndexSearcher searcher;
    private Analyzer analyzer;

    private String field = "title";
    private List<String> documents;

    private List<URI> uriDocuments;

    private Logger log = LoggerFactory.getLogger(getClass());

    protected Logger getLog() {
        return log;
    }

    @Before
    public void setUp() {
        try {
            // create index setup
            analyzer = new EnglishAnalyzer();

            // add documents
            String doc1 = "Lucene in Action";
            String doc2 = "Lucene for Dummies";
            String doc3 = "Managing Gigabytes";
            String doc4 = "The Art of Computer Science";
            String doc5 = "Tony's Special Book on Lucene";
            String doc6 = "Lots of lucenes of awesomeness";
            String doc7 = "Tony is pretty awesome really";

            URI uri1 = URI.create("http://www.ebi.ac.uk/fgpt/foo");
            URI uri2 = URI.create("http://www.ebi.ac.uk/fgpt/bar");

            documents = new ArrayList<>();
            documents.add(doc1);
            documents.add(doc2);
            documents.add(doc3);
            documents.add(doc4);
            documents.add(doc5);
            documents.add(doc6);
            documents.add(doc7);

            uriDocuments = new ArrayList<>();
            uriDocuments.add(uri1);
            uriDocuments.add(uri2);

            // add some data to the index
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            Directory index = new RAMDirectory();
            IndexWriter w = new IndexWriter(index, config);
            for (String doc : documents) {
                addDoc(w, doc);
            }
            for (URI uri : uriDocuments) {
                addURIDoc(w, uri);
            }
            w.close();

            // create a searcher that can search this index
            IndexReader reader = DirectoryReader.open(index);
            searcher = new IndexSearcher(reader);
        }
        catch (IOException e) {
            e.printStackTrace();
            fail();
        }
    }

    public void addDoc(IndexWriter w, String s) {
        try {
            Document doc = new Document();
            doc.add(new Field(field, s, Field.Store.YES, Field.Index.ANALYZED));
            w.addDocument(doc);
        }
        catch (IOException e) {
            e.printStackTrace();
            fail();
        }
    }

    public void addURIDoc(IndexWriter w, URI uri) {
        try {
            Document doc = new Document();
            doc.add(new Field(field, uri.toString(), Field.Store.YES, Field.Index.NOT_ANALYZED));
            w.addDocument(doc);
        }
        catch (IOException e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testQuery() {
        try {
            // build a query
            for (String query : documents) {
                getLog().debug("Analyzing " + query);
                try (TokenStream stream = analyzer.tokenStream(field, new StringReader(query))) {
                    stream.reset();
                    CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
                    while (stream.incrementToken()) {
                        String term = termAtt.toString();
                        getLog().debug("\tNext term = " + term);

                        // now query index for this term and verify the document with title matching query is returned
                        int hitsPerPage = 10;
                        TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage);
                        getLog().debug("Performing lucene query for '" + term + "'");
                        Query q = new TermQuery(new Term(field, term));
                        searcher.search(q, collector);
                        ScoreDoc[] hits = collector.topDocs().scoreDocs;

                        // print results
                        Set<String> results = new HashSet<>();
                        for (int i = 0; i < hits.length; ++i) {
                            int docId = hits[i].doc;
                            Document d = searcher.doc(docId);
                            results.add(d.get("title"));
                            getLog().debug("Result " + (i + 1) + ": " + d.get("title"));
                        }
                        assertTrue("Results does not contain expected title '" + query + "'", results.contains(query));
                    }
                }
            }
        }
        catch (IOException e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testURIQuery() {
        try {
            // build a query
            for (URI query : uriDocuments) {
                // query index for this uri and verify the document with title matching query is returned
                int hitsPerPage = 10;
                TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage);
                getLog().debug("Performing lucene query for <" + query.toString() + ">");
                Query q = new TermQuery(new Term(field, query.toString()));
                searcher.search(q, collector);
                ScoreDoc[] hits = collector.topDocs().scoreDocs;

                // print results
                Set<String> results = new HashSet<>();
                for (int i = 0; i < hits.length; ++i) {
                    int docId = hits[i].doc;
                    Document d = searcher.doc(docId);
                    results.add(d.get("title"));
                    getLog().debug("Result " + (i + 1) + ": " + d.get("title"));
                }
                assertTrue("Results does not contain expected title '" + query + "'", results.contains(query.toString()));
            }
        }
        catch (IOException e) {
            e.printStackTrace();
            fail();
        }
    }
}
