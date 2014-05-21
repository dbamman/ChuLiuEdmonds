package edu.cmu.cs.ark.cle;

import com.google.common.base.Objects;

/** An edge in a directed graph. */
public class Edge {
	public final int source;
	public final int destination;

	public Edge(int source, int destination) {
		this.source = source;
		this.destination = destination;
	}

	@Override public int hashCode() {
		return Objects.hashCode(source, destination);
	}

	@Override public String toString() {
		return Objects.toStringHelper(this)
				.add("source", source)
				.add("destination", destination).toString();
	}

	@Override public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final Edge other = (Edge) obj;
		return this.source == other.source && this.destination == other.destination;
	}
}
