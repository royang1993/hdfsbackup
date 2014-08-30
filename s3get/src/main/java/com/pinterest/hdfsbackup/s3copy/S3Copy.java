package com.pinterest.hdfsbackup.s3copy;

import com.pinterest.hdfsbackup.s3tools.S3CopyOptions;
import com.pinterest.hdfsbackup.s3tools.S3Downloader;
import com.pinterest.hdfsbackup.utils.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Tool;

import java.util.Map;
import java.util.UUID;

/**
 * Created by shawn on 8/26/14.
 */
public class S3Copy extends Configured implements Tool {
  private static final Log log = LogFactory.getLog(S3Copy.class);
  private Configuration conf;

  @Override
  public int run(String[] strings) throws Exception {
    S3CopyOptions options = new S3CopyOptions(strings);
    if (options.helpDefined) {
      return 0;
    }
    options.populateFromConfiguration(this.conf);
    options.showCopyOptions();

    DirWalker dirWalker = new DirWalker(this.conf);
    FileListingInDir srcFileList = dirWalker.walkDir(options.srcPath);

    FSType srcType = FileUtils.getFSType(options.srcPath);
    if (srcType != FSType.S3) {
      log.info("source dir must be S3.");
      return 1;
    }
    // A special case: only download one S3 object.
    if (srcFileList.getFileEntryCount() == 1) {
      S3Downloader s3Downloader = new S3Downloader(this.conf, options, new Progressable() {
        public void progress() {}});
      for (Map.Entry<String, DirEntry> e : srcFileList.getFileEntries()) {
        DirEntry srcEntry = e.getValue();
        if (!srcEntry.isFile) {
          log.info("source object is a dir: " + e.toString());
          return 0;
        }
        if (s3Downloader.DownloadFile(srcEntry, options.destPath, options.verifyChecksum)) {
          return 0;
        } else {
          return 1;
        }
      }
    }

    // TODO(shawn@): support local file.
    if (options.destPath != null) {
      FSType destType = FileUtils.getFSType(options.destPath);
      assert (destType == FSType.HDFS);
    }

    // need to copy from src to dest dir.
    // We will overwrite all contents in dest dir.
    String tempDirRoot = "hdfs:///tmp/" + UUID.randomUUID();
    FileUtils.createHDFSDir(tempDirRoot, this.conf);
    Path mapInputDirPath = new Path(tempDirRoot, "map-input");
    Path redOutputDirPath = new Path(tempDirRoot, "red-output");
    log.info("Use tmp dir: " + tempDirRoot);

    try {
      JobConf job = new JobConf(getConf(), S3Copy.class);

      // Each mapper takes care of a file group. We don't need reducers.
      job.setNumReduceTasks(0);
      int numberMappers = job.getNumMapTasks();
      FilePairPartition partition = new FilePairPartition(numberMappers);
      partition.createFileGroups(srcFileList, options.destPath);
      partition.display(options.verbose);
      if (!partition.writeGroupsToFiles(mapInputDirPath, this.conf)) {
        log.info("failed to write file group files.");
        return 1;
      }

      // set up options
      job.setJobName(String.format("S3Get  %s => %s,  %s checksum",
                                      options.srcPath, options.destPath,
                                      options.verifyChecksum ? "with" : "no"));
      job.setInputFormat(SequenceFileInputFormat.class);
      job.setOutputFormat(TextOutputFormat.class);

      FileInputFormat.addInputPath(job, mapInputDirPath);
      FileOutputFormat.setOutputPath(job, redOutputDirPath);

      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(FilePair.class);

      job.setMapperClass(S3GetMapper.class);
      //job.setReducerClass(S3GetReducer.class);

      log.info("before MR job...");
      RunningJob runningJob = JobClient.runJob(job);
      log.info("after MR job...");
      Counters counters = runningJob.getCounters();
      Counters.Group group = counters.getGroup("org.apache.hadoop.mapreduce.TaskCounter");
      long outputRecords = group.getCounterForName("MAP_OUTPUT_RECORDS").getValue();
      log.info("MR job finished, " + outputRecords + " files failed to download");
      int retcode = (int) outputRecords;
      return retcode;
    }
    finally {
      FileUtils.deleteHDFSDir(tempDirRoot, this.conf);
    }
  }

  @Override
  public void setConf(Configuration entries) {
    this.conf = entries;
  }

  @Override
  public Configuration getConf() {
    return this.conf;
  }
}
