package com.pinterest.hdfsbackup.comparedir;

import com.pinterest.hdfsbackup.s3tools.S3CopyOptions;
import com.pinterest.hdfsbackup.utils.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Tool;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Created by shawn on 9/2/14.
 */
public class CompareDir extends Configured implements Tool {
  private static final Log log = LogFactory.getLog(CompareDir.class);
  private Configuration conf;

  @Override
  public int run(String[] args) throws Exception {
    S3CopyOptions options = new S3CopyOptions(args);
    if (options.helpDefined) {
      return 0;
    }
    options.populateFromConfiguration(this.conf);
    options.showCopyOptions();

    FilePairGroup filePairGroup = null;
    FileListingInDir srcFileList = null;
    FSType srcType = FSType.UNKNOWN;
    FSType destType = FSType.UNKNOWN;

    // We only support S3 and HDFS file system for now.
    if (options.manifestFilename != null) {
      filePairGroup = new FilePairGroup(0);
      long count = filePairGroup.initFromFile(options.manifestFilename);
      if (count <= 0) {
        log.info("Error parsing manifest file: " + options.manifestFilename);
        return 1;
      } else {
        log.info(String.format("Will compare %d files at manifest file: %s",
                                  count, options.manifestFilename));
      }
      srcType = FileUtils.getFSType(filePairGroup.getFilePairs().get(0).srcFile.toString());
      destType = FileUtils.getFSType(filePairGroup.getFilePairs().get(0).destFile.toString());
    } else {
      srcType = FileUtils.getFSType(options.srcPath);
      if (options.destPath != null) {
        destType = FileUtils.getFSType(options.destPath);
      }
    }

    if (srcType != FSType.S3 && srcType != FSType.HDFS) {
      log.info("only HDFS and S3 are supported right now.");
      System.exit(1);
    }
    if (options.destPath != null && destType != FSType.HDFS && destType != FSType.S3) {
      log.info("only HDFS and S3 are supported right now.");
      System.exit(1);
    }

    // Walk the src and dest dir.
    if (options.manifestFilename == null) {
      DirWalker dirWalker = new DirWalker(conf);
      srcFileList = dirWalker.walkDir(options.srcPath);
      srcFileList.display(options.verbose);
      if (options.destPath == null) {
        return 0;
      }

      FileListingInDir destFileList = dirWalker.walkDir(options.destPath);
      destFileList.display(options.verbose);

      List<Pair<DirEntry, DirEntry>> diffPairs = new LinkedList<Pair<DirEntry, DirEntry>>();
      List<Pair<DirEntry, DirEntry>> samePairs = new LinkedList<Pair<DirEntry, DirEntry>>();
      boolean compareDir = false;
      if (!srcFileList.compare(destFileList, diffPairs, samePairs, compareDir)) {
        log.info(String.format("Error: dirs %s | %s don't match:: %d diff files",
                                  options.srcPath, options.destPath, diffPairs.size()));

        for (Pair<DirEntry, DirEntry> pair : diffPairs) {
          log.info((pair.getL() != null ? pair.getL().toString() : " null ") + " :: " +
                       (pair.getR() != null ? pair.getR().toString() : " null"));
        }
        return 1;
      }
      if (!options.compareChecksum) {
        log.info(String.format("dirs \"%s\" | \"%s\" match", options.srcPath, options.destPath));
        return 0;
      }
    }

    // Need to compare checksums for all file pairs.
    String tempDirRoot = "hdfs:///tmp/" + UUID.randomUUID();
    FileUtils.createHDFSDir(tempDirRoot, this.conf);
    Path mapInputDirPath = new Path(tempDirRoot, "map-input");
    Path redOutputDirPath = new Path(tempDirRoot, "red-output");
    log.info("Use tmp dir: " + tempDirRoot);

    try {
      JobConf job = new JobConf(getConf(), CompareDir.class);

      // Each mapper takes care of a file group. We don't need reducers.
      job.setNumReduceTasks(0);
      int numberMappers = job.getNumMapTasks();
      FilePairPartition partition = new FilePairPartition(numberMappers);
      // Not include dir in the file comparison.
      if (srcFileList != null) {
        partition.createFileGroups(srcFileList, options.destPath, false);
        job.setJobName(String.format("CompareDir  %s <=> %s,  %s checksum",
                                        options.srcPath, options.destPath,
                                        options.verifyChecksum ? "with" : "no"));
      } else {
        partition.createFileGroupsFromFilePairs(filePairGroup.getFilePairs());
        job.setJobName(String.format("CompareDir manifest = %s, %s checksum",
                                        options.manifestFilename,
                                        options.verifyChecksum ? "with" : "no"));
      }
      partition.display(options.verbose);
      if (!partition.writeGroupsToFiles(mapInputDirPath, this.conf)) {
        log.info("failed to write file group files.");
        return 1;
      }

      // set up options
      job.setInputFormat(SequenceFileInputFormat.class);
      job.setOutputFormat(TextOutputFormat.class);

      FileInputFormat.addInputPath(job, mapInputDirPath);
      FileOutputFormat.setOutputPath(job, redOutputDirPath);

      job.setOutputKeyClass(Text.class);
      job.setOutputValueClass(FilePair.class);
      job.setMapperClass(CompareDirMapper.class);

      log.info("before MR job...");
      RunningJob runningJob = JobClient.runJob(job);
      log.info("after MR job...");
      Counters counters = runningJob.getCounters();
      Counters.Group group = counters.getGroup("org.apache.hadoop.mapreduce.TaskCounter");
      long outputRecords = group.getCounterForName("MAP_OUTPUT_RECORDS").getValue();
      log.info("MR job finished, found " + outputRecords + " mismatched file pairs.");
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



