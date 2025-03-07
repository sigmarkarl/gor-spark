package gorsat.spark;

import gorsat.BatchedPipeStepIteratorAdaptor;
import gorsat.BatchedReadSource;
import gorsat.process.GorPipe;
import gorsat.process.PipeInstance;
import gorsat.process.PipeOptions;
import gorsat.process.SparkPipeInstance;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.types.StructType;
import org.gorpipe.gor.model.GenomicIterator;
import org.gorpipe.gor.model.RowBase;
import org.gorpipe.gor.monitor.GorMonitor;
import org.gorpipe.model.gor.RowObj;
import org.gorpipe.spark.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class GorPartitionReader implements PartitionReader<InternalRow> {
    GenomicIterator iterator;
    SparkGorRow sparkRow;
    GorMonitor sparkGorMonitor;
    GorRangeInputPartition p;
    ExpressionEncoder.Serializer<Row> serializer;
    String redisUri;
    String streamKey;
    String jobId;
    String useCpp;
    String projectRoot;
    String cacheDir;
    String configFile;
    String aliasFile;
    String securityContext;
    boolean nor = false;

    public GorPartitionReader(StructType schema, GorRangeInputPartition gorRangeInputPartition, String redisUri, String streamKey, String jobId, String projectRoot, String cacheDir, String configFile, String aliasFile, String securityContext, String useCpp) {
        ExpressionEncoder<Row> encoder = RowEncoder.apply(schema);
        serializer = encoder.createSerializer();
        sparkRow = new SparkGorRow(schema);
        p = gorRangeInputPartition;
        this.redisUri = redisUri;
        this.streamKey = streamKey;
        this.jobId = jobId;
        this.useCpp = useCpp;
        this.projectRoot = projectRoot;
        this.cacheDir = cacheDir;
        this.configFile = configFile;
        this.aliasFile = aliasFile;
        this.securityContext = securityContext;
    }

    private String parseMultiplePaths(Path epath) {
        String epathstr = p.path;
        if (Files.isDirectory(epath)) {
            try {
                epathstr = Files.walk(epath).skip(1).map(Path::toString).filter(p -> p.endsWith(".gorz")).collect(Collectors.joining(" "));
            } catch (IOException e) {}
        }
        return epathstr;
    }

    private GenomicIterator iteratorFromFile(SparkPipeInstance pi) {
        boolean useNative = useCpp != null && useCpp.equalsIgnoreCase("true");
        String seek = useNative ? "cmd " : "gor ";

        Path epath = Paths.get(p.path);
        String epathstr = parseMultiplePaths(epath);
        String spath = useNative ? "cgor #(S:-p chr:pos) " + p.path + "}" : epathstr;
        String s = p.filterColumn != null && p.filterColumn.length() > 0 ? "-s " + p.filterColumn + " " : "";
        String path = seek + (p.filterFile == null ? p.filter == null ? s + spath : s + "-f " + p.filter + " " + spath : s + "-ff " + p.filterFile + " " + spath);
        String[] args = {path};
        PipeOptions options = new PipeOptions();
        options.parseOptions(args);
        pi.subProcessArguments(options);

        GenomicIterator rowSource = pi.theInputSource();
        if(p.chr!=null&&p.chr.length()>0) rowSource.seek(p.chr, p.start);

        if (redisUri != null && redisUri.length() > 0) {
            return new BatchedReadSource(rowSource, GorPipe.brsConfig(), rowSource.getHeader(), sparkGorMonitor);
        } else {
            return rowSource;
        }
    }

    private GenomicIterator iteratorWithPipeSteps(PipeInstance pi) {
        pi.init(p.query, false, null);

        GenomicIterator rowSource = pi.theInputSource();
        if(p.chr!=null&&p.chr.length()>0) rowSource.seek(p.chr, p.start);
        return new BatchedPipeStepIteratorAdaptor(rowSource, pi.getPipeStep(), rowSource.getHeader(), GorPipe.brsConfig());
    }

    void initIterator() {
        sparkGorMonitor = GorSparkUtilities.getSparkGorMonitor(jobId, redisUri, streamKey);
        sparkGorMonitor.setCancelled(false);

        SparkSessionFactory sessionFactory = new SparkSessionFactory(null, projectRoot, cacheDir, configFile, aliasFile, securityContext, sparkGorMonitor);
        GorSparkSession gorPipeSession = (GorSparkSession) sessionFactory.create();
        SparkPipeInstance pi = new SparkPipeInstance(gorPipeSession.getGorContext());

        if(p.query!=null) {
            iterator = iteratorWithPipeSteps(pi);
            nor = p.query.toLowerCase().startsWith("nor ") || p.query.toLowerCase().startsWith("norrows ") || p.query.toLowerCase().startsWith("norcmd ") || p.query.toLowerCase().startsWith("cmd -n ");
        } else {
            iterator = iteratorFromFile(pi);
        }
    }

    @Override
    public boolean next() {
        if( iterator == null ) {
            initIterator();
        }
        boolean hasNext = iterator.hasNext();
        if( hasNext ) {
            org.gorpipe.gor.model.Row gorrow = iterator.next();
            if (nor) {
                String rowstr = gorrow.otherCols();
                int[] sa = RowObj.splitArray(rowstr);
                gorrow = new RowBase("chrN", 0, rowstr, sa, null);
            }
            if (p.tag != null) gorrow = gorrow.rowWithAddedColumn(p.tag);
            hasNext = p.chr == null || (gorrow.chr.equals(p.chr) && (p.end == -1 || gorrow.pos <= p.end));
            sparkRow.row = gorrow;
        }
        return hasNext;
    }

    @Override
    public InternalRow get() {
        return serializer.apply(sparkRow);
    }

    @Override
    public void close() {
        if(iterator != null) iterator.close();
    }
}
