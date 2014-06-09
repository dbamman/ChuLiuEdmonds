package edu.cmu.cs.ark.cle;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.*;
import com.google.common.primitives.Doubles;

import java.util.*;

import static com.google.common.collect.ImmutableSet.copyOf;
import static com.google.common.collect.Iterables.concat;
import static edu.cmu.cs.ark.cle.EdgeQueueMap.EdgeQueue;
import static edu.cmu.cs.ark.cle.Weighted.weighted;
import static java.util.Collections.singleton;

/**
 * Chu-Liu-Edmonds' algorithm for finding a maximum branching in a complete, directed graph in O(n^2) time.
 * Implementation is based on Tarjan's "Finding Optimum Branchings" paper:
 * http://cw.felk.cvut.cz/lib/exe/fetch.php/courses/a4m33pal/cviceni/tarjan-finding-optimum-branchings.pdf
 * and Camerini et al. 190
 *
 * @author sthomson@cs.cmu.edu, swabha@cs.cmu.edu
 */
public class ChuLiuEdmonds {
	/** Represents the subgraph that gets iteratively built up in the CLE algorithm. */
	private static class PartialSolution<V> {
		// Partition representing the strongly connected components (SCCs).
		private final Partition<V> stronglyConnected;
		// Partition representing the weakly connected components (WCCs).
		private final Partition<V> weaklyConnected;
		// An invariant of the CLE algorithm is that each SCC always has at most one incoming edge.
		// You can think of these edges as implicitly defining a graph with SCCs as nodes.
		private final Map<V, Weighted<Edge<V>>> incomingEdgeByScc;
		// History of edges we've added, and for each, a list of edges it would exclude.
		// More recently added edges get priority over less recently added edges when reconstructing the final tree.
		private final LinkedList<ExclusiveEdge<V>> edgesAndWhatTheyExclude;
		// a priority queue of incoming edges for each SCC.
		private final EdgeQueueMap<V> unseenIncomingEdges;
		// running sum of weights.
		// edge weights are adjusted as we go to take into account the fact that we have an extra edge in each cycle
		private double score;

		private PartialSolution(Partition<V> stronglyConnected,
								Partition<V> weaklyConnected,
								Map<V, Weighted<Edge<V>>> incomingEdgeByScc,
								LinkedList<ExclusiveEdge<V>> edgesAndWhatTheyExclude,
								EdgeQueueMap<V> unseenIncomingEdges,
								double score) {
			this.stronglyConnected = stronglyConnected;
			this.weaklyConnected = weaklyConnected;
			this.incomingEdgeByScc = incomingEdgeByScc;
			this.edgesAndWhatTheyExclude = edgesAndWhatTheyExclude;
			this.unseenIncomingEdges = unseenIncomingEdges;
			this.score = score;
		}

		public static <T> PartialSolution<T> create(WeightedGraph<T> graph, Set<Edge<T>> required, Set<Edge<T>> banned) {
			final Partition<T> stronglyConnected = Partition.singletons(graph.getNodes());
			return new PartialSolution<T>(
					stronglyConnected,
					Partition.singletons(graph.getNodes()),
					Maps.<T, Weighted<Edge<T>>>newHashMap(),
					Lists.<ExclusiveEdge<T>>newLinkedList(),
					getEdgesByDestination(graph, required, banned, stronglyConnected),
					0.0
			);
		}

		public static <T> PartialSolution<T> create(WeightedGraph<T> graph, T root, Set<Edge<T>> required, Set<Edge<T>> banned) {
			final Partition<T> stronglyConnected = Partition.singletons(graph.getNodes());
			return new PartialSolution<T>(
					stronglyConnected,
					Partition.singletons(graph.getNodes()),
					Maps.<T, Weighted<Edge<T>>>newHashMap(),
					Lists.<ExclusiveEdge<T>>newLinkedList(),
					getEdgesByDestination(graph, root, required, banned, stronglyConnected),
					0.0
			);
		}

		public Set<V> getNodes() {
			return stronglyConnected.getNodes();
		}

		/** Groups edges by their destination component. O(n^2) */
		private static <T> EdgeQueueMap<T> getEdgesByDestination(WeightedGraph<T> graph,
																 T root,
																 Set<Edge<T>> required,
																 Set<Edge<T>> banned,
																 Partition<T> stronglyConnected) {
			final EdgeQueueMap<T> incomingEdges = getEdgesByDestination(graph, required, banned, stronglyConnected);
			// Throw out incoming edges for the root node.
			incomingEdges.queueByDestination.remove(root);
			return incomingEdges;
		}

		private static <T> EdgeQueueMap<T> getEdgesByDestination(WeightedGraph<T> graph,
																 Set<Edge<T>> required,
																 Set<Edge<T>> banned,
																 Partition<T> stronglyConnected) {
			final Function<Edge<T>, T> byDest = new Function<Edge<T>, T>() {
				@Override public T apply(Edge<T> input) { return input.destination; }
			};
			final ListMultimap<T, Edge<T>> requiredByDestination = Multimaps.index(required, byDest);
			final EdgeQueueMap<T> incomingEdges = new EdgeQueueMap<T>(stronglyConnected);
			for (T destinationNode : graph.getNodes()) {
				final List<Edge<T>> requiredEdgesForDest = requiredByDestination.get(destinationNode);
				final Optional<T> requiredDest = requiredEdgesForDest.isEmpty() ?
						Optional.<T>absent() :
						Optional.of(requiredEdgesForDest.get(0).source);
				for (Weighted<Edge<T>> inEdge : graph.getIncomingEdges(destinationNode)) {
					T sourceNode = inEdge.val.source;
					if (sourceNode.equals(destinationNode)) continue; // Skip autocycle edges
					if (requiredDest.isPresent() && !sourceNode.equals(requiredDest.get())) {
						// Skip any edge that might compete with a required edge
						continue;
					}
					if (banned.contains(Edge.from(sourceNode).to(destinationNode))) {
						// Skip banned edges
						continue;
					}
					final double weight = graph.getWeightOf(sourceNode, destinationNode);
					if (weight != Double.NEGATIVE_INFINITY) {
						incomingEdges.addEdge(inEdge);
					}
				}
			}
			return incomingEdges;
		}

		/**
		 * Given an edge that completes a cycle, merge all SCCs on that cycle into one SCC.
		 * Returns the new component.
		 */
		private V merge(Weighted<Edge<V>> newEdge, EdgeQueueMap<V> unseenIncomingEdges) {
			// Find edges connecting SCCs on the path from newEdge.destination to newEdge.source
			final List<Weighted<Edge<V>>> cycle = getCycle(newEdge);
			// build up list of queues that need to be merged, with the edge they would exclude
			final List<Pair<EdgeQueue<V>, Weighted<Edge<V>>>> queuesToMerge = Lists.newLinkedList();
			for (Weighted<Edge<V>> currentEdge : cycle) {
				final V destination = stronglyConnected.componentOf(currentEdge.val.destination);
				final EdgeQueue<V> queue = unseenIncomingEdges.queueByDestination.get(destination);
				// if we choose an edge in queue, we'll have to throw out currentEdge at the end
				// (each SCC can have only one incoming edge).
				queuesToMerge.add(Pair.of(queue, currentEdge));
				unseenIncomingEdges.queueByDestination.remove(destination);
			}
			// Merge all SCCs on the cycle into one
			for (Weighted<Edge<V>> e : cycle) {
				stronglyConnected.merge(e.val.source, e.val.destination);
			}
			V component = stronglyConnected.componentOf(newEdge.val.destination);
			// merge the queues and put the merged queue back into our map under the new component
			unseenIncomingEdges.merge(component, queuesToMerge);
			// keep our implicit graph of SCCs up to date:
			// we just created a cycle, so all in-edges have sources inside the new component
			// i.e. there is no edge with source outside component, and destination inside component
			incomingEdgeByScc.remove(component);
			return component;
		}

		/** Gets the cycle of edges between SCCs that newEdge creates */
		private List<Weighted<Edge<V>>> getCycle(Weighted<Edge<V>> newEdge) {
			final List<Weighted<Edge<V>>> cycle = Lists.newLinkedList();
			// circle around backward in the implicit graph until you get back to where you started
			Weighted<Edge<V>> edge = newEdge;
			cycle.add(edge);
			while (!stronglyConnected.sameComponent(edge.val.source, newEdge.val.destination)) {
				edge = incomingEdgeByScc.get(stronglyConnected.componentOf(edge.val.source));
				cycle.add(edge);
			}
			return cycle;
		}

		/**
		 * Adds the given edge to this subgraph, merging SCCs if necessary
		 * @return the new SCC, if adding edge created a cycle
		 */
		public Optional<V> addEdge(ExclusiveEdge<V> wEdgeAndExcludes) {
			final Edge<V> edge = wEdgeAndExcludes.edge;
			final double weight = wEdgeAndExcludes.weight;
			final Weighted<Edge<V>> wEdge = weighted(edge, weight);
			score += weight;
			final V destinationScc = stronglyConnected.componentOf(edge.destination);
			edgesAndWhatTheyExclude.addFirst(wEdgeAndExcludes);
			incomingEdgeByScc.put(destinationScc, wEdge);
			if (!weaklyConnected.sameComponent(edge.source, edge.destination)) {
				// Edge connects two different WCCs. Including it won't create a new cycle
				weaklyConnected.merge(edge.source, edge.destination);
				return Optional.absent();
			} else {
				// Edge is contained within one WCC. Including it will create a new cycle.
				return Optional.of(merge(wEdge, unseenIncomingEdges));
			}
		}

		/**
		 * Recovers the optimal arborescence.
		 *
		 * Each SCC can only have 1 edge entering it: the edge that we added most recently.
		 * So we work backwards, adding edges unless they conflict with edges we've already added.
		 * Runtime is O(n^2) in the worst case.
		 */
		private Weighted<Arborescence<V>> recoverBestArborescence() {
			final Map<V, V> parents = Maps.newHashMap();
			final Set<Edge> excluded = Sets.newHashSet();
			// start with the most recent
			while (!edgesAndWhatTheyExclude.isEmpty()) {
				final ExclusiveEdge<V> edgeAndWhatItExcludes = edgesAndWhatTheyExclude.pollFirst();
				final Edge<V> edge = edgeAndWhatItExcludes.edge;
				if(!excluded.contains(edge)) {
					excluded.addAll(edgeAndWhatItExcludes.excluded);
					parents.put(edge.destination, edge.source);
				}
			}
			return weighted(Arborescence.of(parents), score);
		}

		public Optional<ExclusiveEdge<V>> popBestEdge(V component) {
			return popBestEdge(component, Arborescence.<V>empty());
		}

		/** Always breaks ties in favor of edges in `best` */
		public Optional<ExclusiveEdge<V>> popBestEdge(V component, Arborescence<V> best) {
			return unseenIncomingEdges.popBestEdge(component, best);
		}
	}

	/**
	 * Represents a subset of all possible spanning arborescences: those that contain all of `required` and
	 * none of `banned`.
	 * `bestArborescence` is the best arborescence in this subset.
	 * `weightOfNextBest` is the score of the second best.
	 * `edgeToBan`,the edge you need to ban in order to get the second best (i.e. the next second will be the
	 * 1-best arborescence with all of `required`, and none of `banned` U {`edgeToBan`}..
	 * Comparison is on `weightOfNextBest`. The assumption is that `bestArborescence` has already been added
	 * to the k-best list, and we're trying to decide whether the next best in this subset is k+1-best overall.
	 */
	static class SubsetOfSolutions<V> implements Comparable<SubsetOfSolutions<V>> {
		final double weightOfNextBest;
		final Edge<V> edgeToBan;
		final Weighted<Arborescence<V>> bestArborescence;
		final Set<Edge<V>> required;
		final Set<Edge<V>> banned;

		public SubsetOfSolutions(double weightOfNextBest,
								 Edge<V> edgeToBan,
								 Weighted<Arborescence<V>> bestArborescence,
								 Set<Edge<V>> required,
								 Set<Edge<V>> banned) {
			this.weightOfNextBest = weightOfNextBest;
			this.edgeToBan = edgeToBan;
			this.bestArborescence = bestArborescence;
			this.required = required;
			this.banned = banned;
		}

		@Override
		public int compareTo(SubsetOfSolutions<V> other) {
			return Doubles.compare(weightOfNextBest, other.weightOfNextBest);
		}
	}


	/**
	 * Find an optimal arborescence of the given graph.
	 */
	public static <V> Weighted<Arborescence<V>> getMaxArborescence(WeightedGraph<V> graph) {
		final Set<Edge<V>> empty = ImmutableSet.of();
		return getMaxArborescence(PartialSolution.create(graph, empty, empty));
	}

	/**
	 * Find an optimal arborescence of the given graph, rooted in the given node.
	 */
	public static <V> Weighted<Arborescence<V>> getMaxArborescence(WeightedGraph<V> graph,
																   V root) {
		final Set<Edge<V>> empty = ImmutableSet.of();
		return getMaxArborescence(graph, root, empty, empty);
	}

	static <V> Weighted<Arborescence<V>> getMaxArborescence(WeightedGraph<V> graph,
															V root,
															Set<Edge<V>> required,
															Set<Edge<V>> banned) {
		return getMaxArborescence(PartialSolution.create(graph, root, required, banned));
	}

	static <V> Weighted<Arborescence<V>> getMaxArborescence(WeightedGraph<V> graph,
															Set<Edge<V>> required,
															Set<Edge<V>> banned) {
		return getMaxArborescence(PartialSolution.create(graph, required, banned));
	}

	private static <V> Weighted<Arborescence<V>> getMaxArborescence(PartialSolution<V> partialSolution) {
		// In the beginning, subgraph has no edges, so no SCC has in-edges.
		final Queue<V> componentsWithNoInEdges = Lists.newLinkedList(partialSolution.getNodes());

		// Work our way through all componentsWithNoInEdges, in no particular order
		while (!componentsWithNoInEdges.isEmpty()) {
			final V component = componentsWithNoInEdges.poll();
			// find maximum edge entering 'component' from outside 'component'.
			final Optional<ExclusiveEdge<V>> oMaxInEdge = partialSolution.popBestEdge(component);
			if (!oMaxInEdge.isPresent()) continue; // No in-edges left to consider for this component. Done with it!
			final ExclusiveEdge<V> maxInEdge = oMaxInEdge.get();
			// add the new edge to subgraph, merging SCCs if necessary
			final Optional<V> newComponent = partialSolution.addEdge(maxInEdge);
			if (newComponent.isPresent()) {
				// addEdge created a cycle/component, which means the new component doesn't have any incoming edges
				componentsWithNoInEdges.add(newComponent.get());
			}
		}
		// Once no component has incoming edges left to consider, it's time to recover the optimal branching.
		return partialSolution.recoverBestArborescence();
	}

	static <V> Optional<SubsetOfSolutions<V>> scoreSubsetOfSolutions(WeightedGraph<V> graph,
																	 V root,
																	 Set<Edge<V>> required,
																	 Set<Edge<V>> banned,
																	 Weighted<Arborescence<V>> wBestArborescence) {
		final Optional<Pair<Edge<V>, Double>> oEdgeToBanAndDiff =
				getNextBestArborescence(graph, root, required, banned, wBestArborescence.val);
		if (oEdgeToBanAndDiff.isPresent()) {
			final Pair<Edge<V>, Double> edgeToBanAndDiff = oEdgeToBanAndDiff.get();
			return Optional.of(new SubsetOfSolutions<V>(
					wBestArborescence.weight - edgeToBanAndDiff.second,
					edgeToBanAndDiff.first,
					wBestArborescence,
					required,
					banned));
		} else {
			return Optional.absent();
		}
	}

	/**
	 * Finds the edge you need to ban in order to get the second best solution (and how much worse that
	 * second best solution will be)
	 * Corresponds to the NEXT function in Camerini et al. 1980
	 */
	private static <V> Optional<Pair<Edge<V>, Double>> getNextBestArborescence(WeightedGraph<V> graph,
																			   V root,
																			   Set<Edge<V>> required,
																			   Set<Edge<V>> banned,
																			   Arborescence<V> bestArborescence) {
		final PartialSolution<V> partialSolution = PartialSolution.create(graph, root, required, banned);

		// In the beginning, subgraph has no edges, so no SCC has in-edges.
		final Queue<V> componentsWithNoInEdges = Lists.newLinkedList(graph.getNodes());

		double bestDifference = Double.POSITIVE_INFINITY;
		Optional<ExclusiveEdge<V>> bestEdgeToKickOut = Optional.absent();

		// Work our way through all componentsWithNoInEdges, in no particular order
		while (!componentsWithNoInEdges.isEmpty()) {
			final V component = componentsWithNoInEdges.poll();
			// find maximum edge entering 'component' from the outside.
			// break ties in favor of edges in bestArborescence
			final Optional<ExclusiveEdge<V>> oMaxInEdge = partialSolution.popBestEdge(component, bestArborescence);
			if (!oMaxInEdge.isPresent()) continue; // No in-edges left to consider for this component. Done with it!
			final ExclusiveEdge<V> maxInEdge = oMaxInEdge.get();
			if (bestArborescence.parents.get(maxInEdge.edge.destination) == maxInEdge.edge.source && !required.contains(maxInEdge.edge)) {
				final Optional<ExclusiveEdge<V>> oAlternativeEdge =
						seek(maxInEdge, bestArborescence, partialSolution.unseenIncomingEdges.queueByDestination.get(component));
				if (oAlternativeEdge.isPresent()) {
					final ExclusiveEdge<V> alternativeEdge = oAlternativeEdge.get();
					final double difference = maxInEdge.weight - alternativeEdge.weight;
					if (difference < bestDifference) {
						bestDifference = difference;
						bestEdgeToKickOut = Optional.of(maxInEdge);
					}
				}
			}
			// add the new edge to subgraph, merging SCCs if necessary
			final Optional<V> newComponent = partialSolution.addEdge(maxInEdge);
			if (newComponent.isPresent()) {
				// addEdge created a cycle, which means the new cycle doesn't have any incoming edges
				componentsWithNoInEdges.add(newComponent.get());
			}
		}
		if (bestEdgeToKickOut.isPresent()) {
			return Optional.of(Pair.of(bestEdgeToKickOut.get().edge, bestDifference));
		} else {
			return Optional.absent();
		}
	}

	/** Determines whether potentialAncestor is an ancestor of node in bestArborescence */
	private static <V> boolean isAncestor(V node, V potentialAncestor, Arborescence<V> bestArborescence) {
		V currentNode = node;
		while (bestArborescence.parents.containsKey(currentNode)) {
			currentNode = bestArborescence.parents.get(currentNode);
			if (currentNode == potentialAncestor) return true;
		}
		return false;
	}

	/**
	 * Finds `nextBestEdge`, the next best alternative to `maxInEdge` for which the tail of
	 * `maxInEdge` is not an ancestor of the source of `nextBestEdge` in `bestArborescence`
	 */
	public static <V> Optional<ExclusiveEdge<V>> seek(ExclusiveEdge<V> maxInEdge,
											   Arborescence<V> bestArborescence,
											   EdgeQueue<V> edgeQueue) {
		Optional<ExclusiveEdge<V>> oNextBestEdge = edgeQueue.popBestEdge();
		while (oNextBestEdge.isPresent()) {
			final ExclusiveEdge<V> nextBestEdge = oNextBestEdge.get();
			if (!isAncestor(nextBestEdge.edge.source, maxInEdge.edge.destination, bestArborescence)) {
				edgeQueue.addEdge(nextBestEdge);
				return oNextBestEdge;
			} else {
				oNextBestEdge = edgeQueue.popBestEdge();
			}
		}
		return Optional.absent();
	}

	/**
	 * Find the k-best arborescences of the given graph, rooted in the given node.
	 * Equivalent to the RANK function in Camerini et al. 1980
	 */
	public static <V> List<Weighted<Arborescence<V>>> getKBestArborescences(WeightedGraph<V> graph, V root, int k) {
		final Set<Edge<V>> empty = ImmutableSet.of();
		final List<Weighted<Arborescence<V>>> results = Lists.newArrayList();
		if (k < 1) return results;
		// 1-best
		final Weighted<Arborescence<V>> best = getMaxArborescence(graph, root);
		results.add(best);
		if (k < 2) return results;
		// reverseOrder b/c we want poll to give us the max
		final PriorityQueue<SubsetOfSolutions<V>> queue =
				new PriorityQueue<SubsetOfSolutions<V>>(3 * k, Collections.reverseOrder());
		// find the edge you need to ban to get the 2nd best
		queue.addAll(scoreSubsetOfSolutions(graph, root, empty, empty, best).asSet());
		for (int j = 2; j <= k && !queue.isEmpty(); j++) {
			final SubsetOfSolutions<V> item = queue.poll();
			// divide this subset into 2: things that have `edgeToBan`, and those that don't
			// We have already pre-calculated that `jthBest` will not contain `edgeToBan`
			final Set<Edge<V>> newBanned = copyOf(concat(item.banned, singleton(item.edgeToBan)));
			final Weighted<Arborescence<V>> jthBest = getMaxArborescence(graph, root, item.required, newBanned);
			assert jthBest.weight == item.weightOfNextBest;
			results.add(jthBest);
			// subset of solutions in item that *don't* have `edgeToBan`, except `jthBest`
			queue.addAll(scoreSubsetOfSolutions(graph, root, item.required, newBanned, jthBest).asSet());
			// subset of solutions in item that *do* have `edgeToBan`, except `bestArborescence`
			final Set<Edge<V>> newRequired = copyOf(concat(item.required, singleton(item.edgeToBan)));
			queue.addAll(scoreSubsetOfSolutions(graph, root, newRequired, item.banned, item.bestArborescence).asSet());
		}
		return results;
	}
}