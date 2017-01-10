package com.orctom.pipeline.model;

import com.orctom.laputa.model.Metric;

import java.io.Serializable;

public class PipelineMetrics extends Metric implements Serializable {

  private String role;
  private long timestamp;

  public PipelineMetrics() {
    this.timestamp = System.currentTimeMillis();
  }

  public PipelineMetrics(String role, Metric metric) {
    super(metric);
    this.role = role;
    this.timestamp = System.currentTimeMillis();
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  @Override
  public String toString() {
    return "PipelineMetrics{" +
        "role='" + role + '\'' +
        ", timestamp=" + timestamp +
        "} " + super.toString();
  }
}
