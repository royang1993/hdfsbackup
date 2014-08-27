package com.pinterest.hdfsbackup.s3get;

import junit.framework.Test;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.ToolRunner;

import java.util.Arrays;

/**
 * Created by shawn on 8/26/14.
 */
public class Main {
  private static final Log log = LogFactory.getLog(Test.class);

  public static void main(String args[]) {
    log.info("run with options: " + Arrays.toString(args));
    Text name = new Text("atadfsd");
    log.info("name = " + name.toString());
    name = new Text("");
    log.info("name = " + name.toString());

    try {
      System.exit(ToolRunner.run(new S3Get(), args));
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }
}
