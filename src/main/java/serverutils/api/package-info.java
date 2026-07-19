/**
 * Supported registry entry points for integrations with ServerUtilities.
 *
 * <p>
 * New integrations should enter through the checked registry facade in this package. Its registration and lookup
 * behavior is a supported contract; callback and action types referenced by that facade retain their existing
 * compatibility status and are not automatically promoted to a blanket stability guarantee. This facade ships in the
 * main ServerUtilities artifact rather than as a standalone API-only artifact.
 */
@javax.annotation.ParametersAreNonnullByDefault
package serverutils.api;
