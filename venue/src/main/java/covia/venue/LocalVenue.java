package covia.venue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.lang.RT;
import convex.did.DID;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;

public class LocalVenue extends Venue {

	private Engine engine;

	public LocalVenue(Engine e) {
		this.engine=e;
	}

	public LocalVenue create(Engine e) {
		return new LocalVenue(e);
	}
	
	@Override
	public Asset getAsset(Hash assetID) {
		AString meta=engine.getMetadata(assetID);
		
		Asset asset= Asset.forString(meta);
		asset.setVenue(this);
		return asset;
	}
	
	public Engine getEngine() {
		return engine;
	}

	@Override
	public DID getDID() {
		return engine.getDID();
	}

	@Override
	public CompletableFuture<Job> invoke(Hash assetID, ACell input) {
		return CompletableFuture.completedFuture(engine.invokeOperation(assetID, input));
	}

	@Override
	public CompletableFuture<Job> invoke(String operation, ACell input) {
		if (operation == null) {
			throw new IllegalArgumentException("Operation must not be null");
		}
		Hash assetID = Hash.parse(operation);
		Job job;
		if (assetID != null) {
			job = engine.invokeOperation(assetID, input);
		} else {
			job = engine.invokeOperation(operation, input);
		}
		return CompletableFuture.completedFuture(job);
	}

	@Override
	public CompletableFuture<Job> getJob(AString jobId) {
		AMap<AString, ACell> status = engine.getJobData(jobId);
		if (status == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId));
		}
		return CompletableFuture.completedFuture(Job.create(status));
	}

	@Override
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(AString jobId) {
		AMap<AString, ACell> status = engine.getJobData(jobId);
		if (status == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Job not found: " + jobId));
		}
		return CompletableFuture.completedFuture(status);
	}

	@Override
	public CompletableFuture<ACell> awaitJobResult(AString jobId) {
		return CompletableFuture.supplyAsync(() -> {
			while (true) {
				AMap<AString, ACell> status = engine.getJobData(jobId);
				if (status == null) {
					throw new IllegalArgumentException("Job not found: " + jobId);
				}
				AString state = RT.ensureString(status.get(Fields.JOB_STATUS_FIELD));
				if (Status.COMPLETE.equals(state)) {
					return RT.get(status, Fields.OUTPUT);
				}
				if (Status.FAILED.equals(state) || Status.REJECTED.equals(state) || Status.CANCELLED.equals(state)) {
					ACell error = status.get(Fields.JOB_ERROR_FIELD);
					String message = (error == null) ? ("Job failed with status " + state) : error.toString();
					throw new JobFailedException(message);
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new JobFailedException(e);
				}
			}
		});
	}

	@Override
	protected AContent getAssetContent(Hash id) throws IOException {

		return engine.getContent(id);
	}

}
