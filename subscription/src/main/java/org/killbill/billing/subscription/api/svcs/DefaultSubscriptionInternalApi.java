/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.subscription.api.svcs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingAlignment;
import org.killbill.billing.catalog.api.Catalog;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.CatalogInternalApi;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanChangeResult;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.catalog.api.PlanPhasePriceOverride;
import org.killbill.billing.catalog.api.PlanPhasePriceOverridesWithCallContext;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementState;
import org.killbill.billing.entitlement.api.EntitlementAOStatusDryRun;
import org.killbill.billing.entitlement.api.EntitlementAOStatusDryRun.DryRunChangeReason;
import org.killbill.billing.entitlement.api.EntitlementSpecifier;
import org.killbill.billing.events.EffectiveSubscriptionInternalEvent;
import org.killbill.billing.invoice.api.DryRunArguments;
import org.killbill.billing.subscription.api.SubscriptionApiBase;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseApiService;
import org.killbill.billing.subscription.api.SubscriptionBaseInternalApi;
import org.killbill.billing.subscription.api.SubscriptionBaseWithAddOns;
import org.killbill.billing.subscription.api.SubscriptionBaseWithAddOnsSpecifier;
import org.killbill.billing.subscription.api.user.DefaultEffectiveSubscriptionEvent;
import org.killbill.billing.subscription.api.user.DefaultSubscriptionBase;
import org.killbill.billing.subscription.api.user.DefaultSubscriptionBaseBundle;
import org.killbill.billing.subscription.api.user.DefaultSubscriptionStatusDryRun;
import org.killbill.billing.subscription.api.user.SubscriptionAndAddOnsSpecifier;
import org.killbill.billing.subscription.api.user.SubscriptionBaseApiException;
import org.killbill.billing.subscription.api.user.SubscriptionBaseBundle;
import org.killbill.billing.subscription.api.user.SubscriptionBaseTransition;
import org.killbill.billing.subscription.api.user.SubscriptionBaseTransitionData;
import org.killbill.billing.subscription.api.user.SubscriptionBuilder;
import org.killbill.billing.subscription.api.user.SubscriptionSpecifier;
import org.killbill.billing.subscription.engine.addon.AddonUtils;
import org.killbill.billing.subscription.engine.dao.SubscriptionDao;
import org.killbill.billing.subscription.engine.dao.model.SubscriptionBundleModelDao;
import org.killbill.billing.subscription.events.SubscriptionBaseEvent;
import org.killbill.billing.subscription.events.bcd.BCDEvent;
import org.killbill.billing.subscription.events.bcd.BCDEventData;
import org.killbill.billing.subscription.exceptions.SubscriptionBaseError;
import org.killbill.billing.util.UUIDs;
import org.killbill.billing.util.bcd.BillCycleDayCalculator;
import org.killbill.billing.util.cache.AccountIdFromBundleIdCacheLoader;
import org.killbill.billing.util.cache.BundleIdFromSubscriptionIdCacheLoader;
import org.killbill.billing.util.cache.Cachable.CacheType;
import org.killbill.billing.util.cache.CacheController;
import org.killbill.billing.util.cache.CacheControllerDispatcher;
import org.killbill.billing.util.cache.CacheLoaderArgument;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.entity.Entity;
import org.killbill.billing.util.entity.Pagination;
import org.killbill.billing.util.entity.dao.DefaultPaginationHelper.SourcePaginationBuilder;
import org.killbill.clock.Clock;
import org.killbill.clock.DefaultClock;
import org.killbill.notificationq.api.NotificationQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;

import static org.killbill.billing.util.entity.dao.DefaultPaginationHelper.getEntityPaginationNoException;

public class DefaultSubscriptionInternalApi extends SubscriptionApiBase implements SubscriptionBaseInternalApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubscriptionInternalApi.class);

    private final AddonUtils addonUtils;
    private final InternalCallContextFactory internalCallContextFactory;
    private final CatalogInternalApi catalogInternalApi;
    private final CacheController<UUID, UUID> accountIdCacheController;
    private final CacheController<UUID, UUID> bundleIdCacheController;

    public static final Comparator<SubscriptionBase> SUBSCRIPTIONS_COMPARATOR = new Comparator<SubscriptionBase>() {

        @Override
        public int compare(final SubscriptionBase o1, final SubscriptionBase o2) {
            if (o1.getCategory() == ProductCategory.BASE) {
                return -1;
            } else if (o2.getCategory() == ProductCategory.BASE) {
                return 1;
            } else {
                return ((DefaultSubscriptionBase) o1).getAlignStartDate().compareTo(((DefaultSubscriptionBase) o2).getAlignStartDate());
            }
        }
    };

    @Inject
    public DefaultSubscriptionInternalApi(final SubscriptionDao dao,
                                          final SubscriptionBaseApiService apiService,
                                          final NotificationQueueService notificationQueueService,
                                          final Clock clock,
                                          final CatalogInternalApi catalogInternalApi,
                                          final AddonUtils addonUtils,
                                          final CacheControllerDispatcher cacheControllerDispatcher,
                                          final InternalCallContextFactory internalCallContextFactory) {
        super(dao, apiService, clock);
        this.addonUtils = addonUtils;
        this.internalCallContextFactory = internalCallContextFactory;
        this.catalogInternalApi = catalogInternalApi;
        this.accountIdCacheController = cacheControllerDispatcher.getCacheController(CacheType.ACCOUNT_ID_FROM_BUNDLE_ID);
        this.bundleIdCacheController = cacheControllerDispatcher.getCacheController(CacheType.BUNDLE_ID_FROM_SUBSCRIPTION_ID);
    }

    private List<SubscriptionSpecifier> verifyAndBuildSubscriptionSpecifiers(final SubscriptionBaseBundle bundle,
                                                                             final boolean hasBaseOrStandalonePlanSpecifier,
                                                                             final Iterable<EntitlementSpecifier> entitlements,
                                                                             final boolean isMigrated,
                                                                             final InternalCallContext context,
                                                                             final DateTime now,
                                                                             final DateTime effectiveDate,
                                                                             final Catalog catalog,
                                                                             final CallContext callContext) throws SubscriptionBaseApiException, CatalogApiException {
        final List<SubscriptionSpecifier> subscriptions = new ArrayList<SubscriptionSpecifier>();
        for (final EntitlementSpecifier entitlement : entitlements) {
            final PlanPhaseSpecifier spec = entitlement.getPlanPhaseSpecifier();
            if (spec == null) {
                // BP already exists
                continue;
            }

            final PlanPhasePriceOverridesWithCallContext overridesWithContext = new DefaultPlanPhasePriceOverridesWithCallContext(entitlement.getOverrides(), callContext);

            final Plan plan = catalog.createOrFindPlan(spec, overridesWithContext, effectiveDate);
            final PlanPhase phase = plan.getAllPhases()[0];
            if (phase == null) {
                throw new SubscriptionBaseError(String.format("No initial PlanPhase for Product %s, term %s and set %s does not exist in the catalog",
                                                              spec.getProductName(), spec.getBillingPeriod().toString(), plan.getPriceListName()));
            }

            // verify the number of subscriptions (of the same kind) allowed per bundle and the existing ones
            if (ProductCategory.ADD_ON.toString().equalsIgnoreCase(plan.getProduct().getCategory().toString())) {
                if (plan.getPlansAllowedInBundle() != -1 && plan.getPlansAllowedInBundle() > 0) {
                    // TODO We should also look to the specifiers being created for validation
                    final List<SubscriptionBase> subscriptionsForBundle = getSubscriptionsForBundle(bundle.getId(), null, context);
                    final int existingAddOnsWithSamePlanName = addonUtils.countExistingAddOnsWithSamePlanName(subscriptionsForBundle, plan.getName());
                    final int currentAddOnsWithSamePlanName = countCurrentAddOnsWithSamePlanName(entitlements, catalog, plan.getName(), effectiveDate, callContext);
                    if ((existingAddOnsWithSamePlanName + currentAddOnsWithSamePlanName) > plan.getPlansAllowedInBundle()) {
                        // a new ADD_ON subscription of the same plan can't be added because it has reached its limit by bundle
                        throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_AO_MAX_PLAN_ALLOWED_BY_BUNDLE, plan.getName());
                    }
                }
            }

            final DateTime bundleStartDate;
            if (hasBaseOrStandalonePlanSpecifier) {
                bundleStartDate = effectiveDate;
            } else {
                final SubscriptionBase baseSubscription = dao.getBaseSubscription(bundle.getId(), catalog, context);
                bundleStartDate = getBundleStartDateWithSanity(bundle.getId(), baseSubscription, plan, effectiveDate, catalog, context);
            }

            final SubscriptionSpecifier subscription = new SubscriptionSpecifier();
            subscription.setRealPriceList(plan.getPriceListName());
            subscription.setEffectiveDate(effectiveDate);
            subscription.setProcessedDate(now);
            subscription.setPlan(plan);
            subscription.setInitialPhase(spec.getPhaseType());
            subscription.setBuilder(new SubscriptionBuilder()
                                            .setId(UUIDs.randomUUID())
                                            .setBundleId(bundle.getId())
                                            .setBundleExternalKey(bundle.getExternalKey())
                                            .setCategory(plan.getProduct().getCategory())
                                            .setBundleStartDate(bundleStartDate)
                                            .setAlignStartDate(effectiveDate)
                                            .setMigrated(isMigrated));

            subscriptions.add(subscription);
        }
        return subscriptions;
    }

    private boolean sanityAndReorderBPOrStandaloneSpecFirst(final Catalog catalog,
                                                            final SubscriptionBaseWithAddOnsSpecifier subscriptionBaseWithAddOnsSpecifier,
                                                            final DateTime effectiveDate,
                                                            final Collection<EntitlementSpecifier> outputEntitlementSpecifier) throws SubscriptionBaseApiException {
        EntitlementSpecifier basePlanSpecifier = null;
        final Collection<EntitlementSpecifier> addOnSpecifiers = new ArrayList<EntitlementSpecifier>();
        final Collection<EntitlementSpecifier> standaloneSpecifiers = new ArrayList<EntitlementSpecifier>();
        try {
            for (final EntitlementSpecifier cur : subscriptionBaseWithAddOnsSpecifier.getEntitlementSpecifiers()) {
                final boolean isBase = isBaseSpecifier(catalog, effectiveDate, cur);
                final boolean isStandalone = isStandaloneSpecifier(catalog, effectiveDate, cur);
                if (isStandalone) {
                    standaloneSpecifiers.add(cur);
                } else if (isBase) {
                    if (basePlanSpecifier == null) {
                        basePlanSpecifier = cur;
                    } else {
                        throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
                    }
                } else {
                    addOnSpecifiers.add(cur);
                }
            }
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }

        if (basePlanSpecifier != null) {
            outputEntitlementSpecifier.add(basePlanSpecifier);
        }
        outputEntitlementSpecifier.addAll(addOnSpecifiers);

        if (!outputEntitlementSpecifier.isEmpty() && !standaloneSpecifiers.isEmpty()) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
        }

        if (standaloneSpecifiers.isEmpty()) {
            return basePlanSpecifier != null;
        } else {
            outputEntitlementSpecifier.addAll(standaloneSpecifiers);
            return true;
        }
    }

    private boolean isBaseSpecifier(final Catalog catalog, final DateTime effectiveDate, final EntitlementSpecifier cur) throws CatalogApiException {
        final Plan inputPlan = catalog.createOrFindPlan(cur.getPlanPhaseSpecifier(), null, effectiveDate);
        return inputPlan.getProduct().getCategory() == ProductCategory.BASE;
    }

    private boolean isStandaloneSpecifier(final Catalog catalog, final DateTime effectiveDate, final EntitlementSpecifier cur) throws CatalogApiException {
        final Plan inputPlan = catalog.createOrFindPlan(cur.getPlanPhaseSpecifier(), null, effectiveDate);
        return inputPlan.getProduct().getCategory() == ProductCategory.STANDALONE;
    }

    @Override
    public List<SubscriptionBaseWithAddOns> createBaseSubscriptionsWithAddOns(final Iterable<SubscriptionBaseWithAddOnsSpecifier> subscriptionWithAddOnsSpecifiers, final boolean renameCancelledBundleIfExist, final InternalCallContext context) throws SubscriptionBaseApiException {
        try {
            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);
            final CallContext callContext = internalCallContextFactory.createCallContext(context);
            final UUID accountId = callContext.getAccountId();

            final Collection<SubscriptionAndAddOnsSpecifier> subscriptionAndAddOns = new ArrayList<SubscriptionAndAddOnsSpecifier>();
            for (final SubscriptionBaseWithAddOnsSpecifier subscriptionBaseWithAddOnsSpecifier : subscriptionWithAddOnsSpecifiers) {
                final DateTime billingRequestedDateRaw = (subscriptionBaseWithAddOnsSpecifier.getBillingEffectiveDate() != null) ?
                                                         context.toUTCDateTime(subscriptionBaseWithAddOnsSpecifier.getBillingEffectiveDate()) : context.getCreatedDate();

                final Collection<EntitlementSpecifier> reorderedSpecifiers = new ArrayList<EntitlementSpecifier>();
                // Note: billingRequestedDateRaw might not be accurate here (add-on with a too early date passed)?
                final boolean hasBaseOrStandalonePlanSpecifier = sanityAndReorderBPOrStandaloneSpecFirst(catalog, subscriptionBaseWithAddOnsSpecifier, billingRequestedDateRaw, reorderedSpecifiers);

                DateTime billingRequestedDate = billingRequestedDateRaw;
                SubscriptionBaseBundle bundle = null;
                if (subscriptionBaseWithAddOnsSpecifier.getBundleId() != null) {
                    bundle = dao.getSubscriptionBundleFromId(subscriptionBaseWithAddOnsSpecifier.getBundleId(), context);
                    if (bundle == null ||
                        (subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey() != null && !subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey().equals(bundle.getExternalKey()))) {
                        throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
                    }
                } else if (subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey() != null &&
                           !hasBaseOrStandalonePlanSpecifier) { // Skip the expensive checks if we are about to create the bundle (validation will be done in SubscriptionDao#createSubscriptionBundle)
                    final SubscriptionBaseBundle tmp = getActiveBundleForKey(subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey(), catalog, context);
                    if (tmp == null) {
                        throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_NO_BP, subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey());
                    } else if (!tmp.getAccountId().equals(accountId)) {
                        throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_ACTIVE_BUNDLE_KEY_EXISTS, subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey());
                    } else {
                        bundle = tmp;
                    }
                }

                SubscriptionBase baseSubscription = null;
                if (bundle != null) {
                    baseSubscription = dao.getBaseSubscription(bundle.getId(), catalog, context);
                    if (baseSubscription != null) {
                        final DateTime baseSubscriptionStartDate = getBaseSubscription(bundle.getId(), context).getStartDate();
                        billingRequestedDate = billingRequestedDateRaw.isBefore(baseSubscriptionStartDate) ? baseSubscriptionStartDate : billingRequestedDateRaw;
                    }
                }

                if (bundle == null && hasBaseOrStandalonePlanSpecifier) {
                    bundle = createBundleForAccount(accountId,
                                                    subscriptionBaseWithAddOnsSpecifier.getBundleExternalKey(),
                                                    renameCancelledBundleIfExist,
                                                    context);
                } else if (bundle != null && baseSubscription != null && hasBaseOrStandalonePlanSpecifier) {
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_BP_EXISTS, bundle.getExternalKey());
                } else if (bundle == null) {
                    log.warn("Invalid specifier: {}", subscriptionBaseWithAddOnsSpecifier);
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
                }

                final SubscriptionAndAddOnsSpecifier subscriptionAndAddOnsSpecifier = new SubscriptionAndAddOnsSpecifier(bundle,
                                                                                                                         billingRequestedDate,
                                                                                                                         verifyAndBuildSubscriptionSpecifiers(bundle,
                                                                                                                                                              hasBaseOrStandalonePlanSpecifier,
                                                                                                                                                              reorderedSpecifiers,
                                                                                                                                                              subscriptionBaseWithAddOnsSpecifier.isMigrated(),
                                                                                                                                                              context,
                                                                                                                                                              context.getCreatedDate(),
                                                                                                                                                              billingRequestedDate,
                                                                                                                                                              catalog,
                                                                                                                                                              callContext));
                subscriptionAndAddOns.add(subscriptionAndAddOnsSpecifier);
            }

            final List<SubscriptionBaseWithAddOns> subscriptionBaseWithAddOns = apiService.createPlansWithAddOns(accountId, subscriptionAndAddOns, catalog, callContext);
            for (final SubscriptionBaseWithAddOns subscriptionBaseWithAO : subscriptionBaseWithAddOns) {
                for (final SubscriptionBase subscriptionBase : subscriptionBaseWithAO.getSubscriptionBaseList()) {
                    bundleIdCacheController.putIfAbsent(subscriptionBase.getId(), subscriptionBaseWithAO.getBundle().getId());
                }
            }
            return subscriptionBaseWithAddOns;
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    private int countCurrentAddOnsWithSamePlanName(final Iterable<EntitlementSpecifier> entitlements,
                                                   final Catalog catalog, final String planName,
                                                   final DateTime effectiveDate, final CallContext callContext) throws CatalogApiException {
        int countCurrentAddOns = 0;
        for (final EntitlementSpecifier entitlement : entitlements) {
            final PlanPhaseSpecifier spec = entitlement.getPlanPhaseSpecifier();
            final PlanPhasePriceOverridesWithCallContext overridesWithContext =
                    new DefaultPlanPhasePriceOverridesWithCallContext(entitlement.getOverrides(), callContext);
            final Plan plan = catalog.createOrFindPlan(spec, overridesWithContext, effectiveDate);

            if (plan.getName().equalsIgnoreCase(planName)
                && plan.getProduct().getCategory() != null
                && ProductCategory.ADD_ON.equals(plan.getProduct().getCategory())) {
                countCurrentAddOns++;
            }
        }
        return countCurrentAddOns;
    }

    @Override
    public void cancelBaseSubscriptions(final Iterable<SubscriptionBase> subscriptions, final BillingActionPolicy policy, int accountBillCycleDayLocal, final InternalCallContext context) throws SubscriptionBaseApiException {

        try {
            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);
            apiService.cancelWithPolicyNoValidationAndCatalog(Iterables.<SubscriptionBase, DefaultSubscriptionBase>transform(subscriptions,
                                                                                                                             new Function<SubscriptionBase, DefaultSubscriptionBase>() {
                                                                                                                       @Override
                                                                                                                       public DefaultSubscriptionBase apply(final SubscriptionBase subscriptionBase) {
                                                                                                                           try {
                                                                                                                               return getDefaultSubscriptionBase(subscriptionBase, catalog, context);
                                                                                                                           } catch (final CatalogApiException e) {
                                                                                                                               throw new RuntimeException(e);
                                                                                                                           }
                                                                                                                       }
                                                                                                                   }),
                                                              policy,
                                                              accountBillCycleDayLocal,
                                                              catalog,
                                                              context);
        } catch (CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }

    }

    @VisibleForTesting
    @Override
    public SubscriptionBaseBundle createBundleForAccount(final UUID accountId, final String bundleKey, final boolean renameCancelledBundleIfExist, final InternalCallContext context) throws SubscriptionBaseApiException {
        final DateTime now = context.getCreatedDate();
        final DefaultSubscriptionBaseBundle bundle = new DefaultSubscriptionBaseBundle(bundleKey, accountId, now, now, now, now);
        if (null != bundleKey && bundleKey.length() > 255) {
            throw new SubscriptionBaseApiException(ErrorCode.EXTERNAL_KEY_LIMIT_EXCEEDED);
        }
        try {
            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);
            final SubscriptionBaseBundle subscriptionBundle = dao.createSubscriptionBundle(bundle, catalog, renameCancelledBundleIfExist, context);
            accountIdCacheController.putIfAbsent(bundle.getId(), accountId);
            return subscriptionBundle;
        } catch (final CatalogApiException e) {
            throw new  SubscriptionBaseApiException(e);
        }
    }

    @Override
    public List<SubscriptionBaseBundle> getBundlesForAccountAndKey(final UUID accountId, final String bundleKey, final InternalTenantContext context) throws SubscriptionBaseApiException {
        final SubscriptionBaseBundle subscriptionBundlesForAccountAndKey = dao.getSubscriptionBundlesForAccountAndKey(accountId, bundleKey, context);
        return subscriptionBundlesForAccountAndKey != null ? ImmutableList.of(subscriptionBundlesForAccountAndKey) : ImmutableList.<SubscriptionBaseBundle>of();
    }

    @Override
    public List<SubscriptionBaseBundle> getBundlesForAccount(final UUID accountId, final InternalTenantContext context) {
        return dao.getSubscriptionBundleForAccount(accountId, context);
    }

    @Override
    public List<SubscriptionBaseBundle> getBundlesForKey(final String bundleKey, final InternalTenantContext context) {
        return dao.getSubscriptionBundlesForKey(bundleKey, context);
    }

    @Override
    public Pagination<SubscriptionBaseBundle> getBundles(final Long offset, final Long limit, final InternalTenantContext context) {
        return getEntityPaginationNoException(limit,
                                              new SourcePaginationBuilder<SubscriptionBundleModelDao, SubscriptionBaseApiException>() {
                                                  @Override
                                                  public Pagination<SubscriptionBundleModelDao> build() {
                                                      return dao.get(offset, limit, context);
                                                  }
                                              },
                                              new Function<SubscriptionBundleModelDao, SubscriptionBaseBundle>() {
                                                  @Override
                                                  public SubscriptionBaseBundle apply(final SubscriptionBundleModelDao bundleModelDao) {
                                                      return SubscriptionBundleModelDao.toSubscriptionBundle(bundleModelDao);
                                                  }
                                              }
                                             );
    }

    @Override
    public Pagination<SubscriptionBaseBundle> searchBundles(final String searchKey, final Long offset, final Long limit, final InternalTenantContext context) {
        return getEntityPaginationNoException(limit,
                                              new SourcePaginationBuilder<SubscriptionBundleModelDao, SubscriptionBaseApiException>() {
                                                  @Override
                                                  public Pagination<SubscriptionBundleModelDao> build() {
                                                      return dao.searchSubscriptionBundles(searchKey, offset, limit, context);
                                                  }
                                              },
                                              new Function<SubscriptionBundleModelDao, SubscriptionBaseBundle>() {
                                                  @Override
                                                  public SubscriptionBaseBundle apply(final SubscriptionBundleModelDao bundleModelDao) {
                                                      return SubscriptionBundleModelDao.toSubscriptionBundle(bundleModelDao);
                                                  }
                                              }
                                             );

    }

    @Override
    public Iterable<UUID> getNonAOSubscriptionIdsForKey(final String bundleKey, final InternalTenantContext context) {
        return dao.getNonAOSubscriptionIdsForKey(bundleKey, context);
    }

    @Override
    public SubscriptionBaseBundle getActiveBundleForKey(final String bundleKey, final Catalog catalog, final InternalTenantContext context) {
        final List<SubscriptionBaseBundle> existingBundles = dao.getSubscriptionBundlesForKey(bundleKey, context);
        for (final SubscriptionBaseBundle cur : existingBundles) {
            final List<SubscriptionBase> subscriptions;
            try {
                subscriptions = dao.getSubscriptions(cur.getId(), ImmutableList.<SubscriptionBaseEvent>of(), catalog, context);
                for (final SubscriptionBase s : subscriptions) {
                    if (s.getCategory() == ProductCategory.ADD_ON) {
                        continue;
                    }
                    if (s.getEndDate() == null || s.getEndDate().compareTo(clock.getUTCNow()) > 0) {
                        return cur;
                    }
                }
            } catch (final CatalogApiException e) {
                log.warn("Failed to get subscriptions for bundleId='{}'", cur.getId(), e);
                return null;
            }
        }
        return null;
    }

    @Override
    public List<SubscriptionBase> getSubscriptionsForBundle(final UUID bundleId,
                                                            @Nullable final DryRunArguments dryRunArguments,
                                                            final InternalTenantContext context) throws SubscriptionBaseApiException {

        try {

            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);

            final List<SubscriptionBaseEvent> outputDryRunEvents = new ArrayList<SubscriptionBaseEvent>();
            final List<SubscriptionBase> outputSubscriptions = new ArrayList<SubscriptionBase>();

            populateDryRunEvents(bundleId, dryRunArguments, outputDryRunEvents, outputSubscriptions, catalog, context);
            final List<SubscriptionBase> result;
            result = dao.getSubscriptions(bundleId, outputDryRunEvents, catalog, context);
            if (result != null && !result.isEmpty()) {
                outputSubscriptions.addAll(result);
            }
            Collections.sort(outputSubscriptions, DefaultSubscriptionInternalApi.SUBSCRIPTIONS_COMPARATOR);

            return createSubscriptionsForApiUse(outputSubscriptions);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public Map<UUID, List<SubscriptionBase>> getSubscriptionsForAccount(final Catalog catalog, final InternalTenantContext context) throws SubscriptionBaseApiException {
        try {
            final Map<UUID, List<SubscriptionBase>> internalSubscriptions = dao.getSubscriptionsForAccount(catalog, context);
            final Map<UUID, List<SubscriptionBase>> result = new HashMap<UUID, List<SubscriptionBase>>();
            for (final UUID bundleId : internalSubscriptions.keySet()) {
                result.put(bundleId, createSubscriptionsForApiUse(internalSubscriptions.get(bundleId)));
            }
            return result;
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public SubscriptionBase getBaseSubscription(final UUID bundleId, final InternalTenantContext context) throws SubscriptionBaseApiException {
        try {
            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);

            final SubscriptionBase result = dao.getBaseSubscription(bundleId, catalog, context);
            if (result == null) {
                throw new SubscriptionBaseApiException(ErrorCode.SUB_GET_NO_SUCH_BASE_SUBSCRIPTION, bundleId);
            }
            return createSubscriptionForApiUse(result);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public SubscriptionBase getSubscriptionFromId(final UUID id, final InternalTenantContext context) throws SubscriptionBaseApiException {
        try {

            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);

            final SubscriptionBase result = dao.getSubscriptionFromId(id, catalog, context);
            if (result == null) {
                throw new SubscriptionBaseApiException(ErrorCode.SUB_INVALID_SUBSCRIPTION_ID, id);
            }
            return createSubscriptionForApiUse(result);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public SubscriptionBaseBundle getBundleFromId(final UUID id, final InternalTenantContext context) throws SubscriptionBaseApiException {
        final SubscriptionBaseBundle result = dao.getSubscriptionBundleFromId(id, context);
        if (result == null) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_ID, id.toString());
        }
        return result;
    }

    @Override
    public void setChargedThroughDate(final UUID subscriptionId, final DateTime chargedThruDate, final InternalCallContext context) throws SubscriptionBaseApiException {
        try {

            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);

            final DefaultSubscriptionBase subscription = (DefaultSubscriptionBase) dao.getSubscriptionFromId(subscriptionId, catalog, context);
            final SubscriptionBuilder builder = new SubscriptionBuilder(subscription)
                    .setChargedThroughDate(chargedThruDate);

            dao.updateChargedThroughDate(new DefaultSubscriptionBase(builder), context);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public List<EffectiveSubscriptionInternalEvent> getAllTransitions(final SubscriptionBase subscription, final InternalTenantContext context) {
        final List<SubscriptionBaseTransition> transitions = subscription.getAllTransitions();
        return convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(subscription, context, transitions);
    }

    @Override
    public List<EffectiveSubscriptionInternalEvent> getBillingTransitions(final SubscriptionBase subscription, final InternalTenantContext context) {
        final List<SubscriptionBaseTransition> transitions = ((DefaultSubscriptionBase) subscription).getBillingTransitions();
        return convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(subscription, context, transitions);
    }

    @Override
    public DateTime getDryRunChangePlanEffectiveDate(final SubscriptionBase subscription,
                                                     final PlanPhaseSpecifier spec,
                                                     final DateTime requestedDateWithMs,
                                                     final BillingActionPolicy requestedPolicy,
                                                     final List<PlanPhasePriceOverride> overrides,
                                                     final InternalCallContext context) throws SubscriptionBaseApiException, CatalogApiException {
        final TenantContext tenantContext = internalCallContextFactory.createTenantContext(context);
        final CallContext callContext = internalCallContextFactory.createCallContext(context);

        // verify the number of subscriptions (of the same kind) allowed per bundle
        final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);
        final DateTime effectiveDate = (requestedDateWithMs != null) ? DefaultClock.truncateMs(requestedDateWithMs) : null;
        final DateTime effectiveCatalogDate = effectiveDate != null ? effectiveDate : context.getCreatedDate();
        final PlanPhasePriceOverridesWithCallContext overridesWithContext = new DefaultPlanPhasePriceOverridesWithCallContext(overrides, callContext);
        final Plan plan = catalog.createOrFindPlan(spec, overridesWithContext, effectiveCatalogDate);
        if (ProductCategory.ADD_ON.toString().equalsIgnoreCase(plan.getProduct().getCategory().toString())) {
            if (plan.getPlansAllowedInBundle() != -1
                && plan.getPlansAllowedInBundle() > 0
                && addonUtils.countExistingAddOnsWithSamePlanName(getSubscriptionsForBundle(subscription.getBundleId(), null, context), plan.getName())
                   >= plan.getPlansAllowedInBundle()) {
                // the plan can be changed to the new value, because it has reached its limit by bundle
                throw new SubscriptionBaseApiException(ErrorCode.SUB_CHANGE_AO_MAX_PLAN_ALLOWED_BY_BUNDLE, plan.getName());
            }
        }
        return apiService.dryRunChangePlan((DefaultSubscriptionBase) subscription, spec, effectiveDate, requestedPolicy, tenantContext);
    }

    @Override
    public List<EntitlementAOStatusDryRun> getDryRunChangePlanStatus(final UUID subscriptionId, @Nullable final String baseProductName, final DateTime requestedDate, final InternalTenantContext context) throws SubscriptionBaseApiException {
        try {

            final Catalog catalog = catalogInternalApi.getFullCatalog(true, true, context);

            final SubscriptionBase subscription = dao.getSubscriptionFromId(subscriptionId, catalog, context);
            if (subscription == null) {
                throw new SubscriptionBaseApiException(ErrorCode.SUB_INVALID_SUBSCRIPTION_ID, subscriptionId);
            }
            if (subscription.getCategory() != ProductCategory.BASE) {
                throw new SubscriptionBaseApiException(ErrorCode.SUB_CHANGE_DRY_RUN_NOT_BP);
            }

            final List<EntitlementAOStatusDryRun> result = new LinkedList<EntitlementAOStatusDryRun>();

            final List<SubscriptionBase> bundleSubscriptions = dao.getSubscriptions(subscription.getBundleId(), ImmutableList.<SubscriptionBaseEvent>of(), catalog, context);
            for (final SubscriptionBase cur : bundleSubscriptions) {
                if (cur.getId().equals(subscriptionId)) {
                    continue;
                }

                // If ADDON is cancelled, skip
                if (cur.getState() == EntitlementState.CANCELLED) {
                    continue;
                }

                final DryRunChangeReason reason;
                // If baseProductName is null, it's a cancellation dry-run. In this case, return all addons, so they are cancelled
                if (baseProductName != null && addonUtils.isAddonIncludedFromProdName(baseProductName, cur.getCurrentPlan(), requestedDate, catalog, context)) {
                    reason = DryRunChangeReason.AO_INCLUDED_IN_NEW_PLAN;
                } else if (baseProductName != null && addonUtils.isAddonAvailableFromProdName(baseProductName, cur.getCurrentPlan(), requestedDate, catalog, context)) {
                    reason = DryRunChangeReason.AO_AVAILABLE_IN_NEW_PLAN;
                } else {
                    reason = DryRunChangeReason.AO_NOT_AVAILABLE_IN_NEW_PLAN;
                }
                final EntitlementAOStatusDryRun status = new DefaultSubscriptionStatusDryRun(cur.getId(),
                                                                                             cur.getCurrentPlan().getProduct().getName(),
                                                                                             cur.getCurrentPhase().getPhaseType(),
                                                                                             cur.getCurrentPlan().getRecurringBillingPeriod(),
                                                                                             cur.getCurrentPriceList().getName(), reason);
                result.add(status);
            }
            return result;
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }

    }

    @Override
    public void updateExternalKey(final UUID bundleId, final String newExternalKey, final InternalCallContext context) {
        dao.updateBundleExternalKey(bundleId, newExternalKey, context);
    }

    private void populateDryRunEvents(@Nullable final UUID bundleId,
                                      @Nullable final DryRunArguments dryRunArguments,
                                      final Collection<SubscriptionBaseEvent> outputDryRunEvents,
                                      final Collection<SubscriptionBase> outputSubscriptions,
                                      final Catalog catalog,
                                      final InternalTenantContext context) throws SubscriptionBaseApiException {
        if (dryRunArguments == null || dryRunArguments.getAction() == null) {
            return;
        }

        final DateTime utcNow = clock.getUTCNow();
        List<SubscriptionBaseEvent> dryRunEvents = null;
        try {
            final PlanPhaseSpecifier inputSpec = dryRunArguments.getPlanPhaseSpecifier();
            final boolean isInputSpecNullOrEmpty = inputSpec == null ||
                                                   (inputSpec.getPlanName() == null && inputSpec.getProductName() == null && inputSpec.getBillingPeriod() == null);

            // Create an overridesWithContext with a null context to indicate this is dryRun and no price overriden plan should be created.
            final PlanPhasePriceOverridesWithCallContext overridesWithContext = new DefaultPlanPhasePriceOverridesWithCallContext(dryRunArguments.getPlanPhasePriceOverrides(), null);
            final Plan plan = isInputSpecNullOrEmpty ?
                              null :
                              catalog.createOrFindPlan(inputSpec, overridesWithContext, utcNow);
            final TenantContext tenantContext = internalCallContextFactory.createTenantContext(context);

            switch (dryRunArguments.getAction()) {
                case START_BILLING:

                    final DefaultSubscriptionBase baseSubscription = (DefaultSubscriptionBase) dao.getBaseSubscription(bundleId, catalog, context);
                    final DateTime startEffectiveDate = dryRunArguments.getEffectiveDate() != null ? context.toUTCDateTime(dryRunArguments.getEffectiveDate()) : utcNow;
                    final DateTime bundleStartDate = getBundleStartDateWithSanity(bundleId, baseSubscription, plan, startEffectiveDate, catalog, context);
                    final UUID subscriptionId = UUIDs.randomUUID();
                    dryRunEvents = apiService.getEventsOnCreation(subscriptionId, startEffectiveDate, bundleStartDate, plan, inputSpec.getPhaseType(), plan.getPriceListName(),
                                                                  startEffectiveDate, catalog, context);
                    final SubscriptionBuilder builder = new SubscriptionBuilder()
                            .setId(subscriptionId)
                            .setBundleId(bundleId)
                            .setBundleExternalKey(null)
                            .setCategory(plan.getProduct().getCategory())
                            .setBundleStartDate(bundleStartDate)
                            .setAlignStartDate(startEffectiveDate);
                    final DefaultSubscriptionBase newSubscription = new DefaultSubscriptionBase(builder, apiService, clock);
                    newSubscription.rebuildTransitions(dryRunEvents, catalog);
                    outputSubscriptions.add(newSubscription);
                    break;

                case CHANGE:
                    final DefaultSubscriptionBase subscriptionForChange = (DefaultSubscriptionBase) dao.getSubscriptionFromId(dryRunArguments.getSubscriptionId(), catalog, context);

                    DateTime changeEffectiveDate = getDryRunEffectiveDate(dryRunArguments.getEffectiveDate(), subscriptionForChange, context);
                    if (changeEffectiveDate == null) {
                        BillingActionPolicy policy = dryRunArguments.getBillingActionPolicy();
                        if (policy == null) {
                            final PlanChangeResult planChangeResult = apiService.getPlanChangeResult(subscriptionForChange, inputSpec, utcNow, tenantContext);
                            policy = planChangeResult.getPolicy();
                        }
                        // We pass null for billingAlignment, accountTimezone, account BCD because this is not available which means that dryRun with START_OF_TERM BillingPolicy will fail
                        changeEffectiveDate = subscriptionForChange.getPlanChangeEffectiveDate(policy, null, -1, context);
                    }
                    dryRunEvents = apiService.getEventsOnChangePlan(subscriptionForChange, plan, plan.getPriceListName(), changeEffectiveDate, true, catalog, context);
                    break;

                case STOP_BILLING:
                    final DefaultSubscriptionBase subscriptionForCancellation = (DefaultSubscriptionBase) dao.getSubscriptionFromId(dryRunArguments.getSubscriptionId(), catalog, context);

                    DateTime cancelEffectiveDate = getDryRunEffectiveDate(dryRunArguments.getEffectiveDate(), subscriptionForCancellation, context);
                    if (dryRunArguments.getEffectiveDate() == null) {
                        BillingActionPolicy policy = dryRunArguments.getBillingActionPolicy();
                        if (policy == null) {
                            final Plan currentPlan = subscriptionForCancellation.getCurrentPlan();
                            final PlanPhaseSpecifier spec = new PlanPhaseSpecifier(currentPlan.getName(),
                                                                                   subscriptionForCancellation.getCurrentPhase().getPhaseType());
                            policy = catalog.planCancelPolicy(spec, subscriptionForCancellation.getStartDate());
                        }
                        // We pass null for billingAlignment, accountTimezone, account BCD because this is not available which means that dryRun with START_OF_TERM BillingPolicy will fail
                        cancelEffectiveDate = subscriptionForCancellation.getPlanChangeEffectiveDate(policy, null, -1, context);
                    }
                    dryRunEvents = apiService.getEventsOnCancelPlan(subscriptionForCancellation, cancelEffectiveDate, true, catalog, context);
                    break;

                default:
                    throw new IllegalArgumentException("Unexpected dryRunArguments action " + dryRunArguments.getAction());
            }
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
        if (dryRunEvents != null && !dryRunEvents.isEmpty()) {
            outputDryRunEvents.addAll(dryRunEvents);
        }
    }

    private DateTime getDryRunEffectiveDate(@Nullable final LocalDate inputDate, final DefaultSubscriptionBase subscription, final InternalTenantContext context) {
        if (inputDate == null) {
            return null;
        }

        // We first use context account reference time to get a candidate)
        final DateTime tmp = context.toUTCDateTime(inputDate);
        // If we realize that the candidate is on the same LocalDate boundary as the subscription startDate but a bit prior we correct it to avoid weird things down the line
        if (inputDate.compareTo(context.toLocalDate(subscription.getStartDate())) == 0 && tmp.compareTo(subscription.getStartDate()) < 0) {
            return subscription.getStartDate();
        } else {
            return tmp;
        }
    }

    @Override
    public void updateBCD(final UUID subscriptionId, final int bcd, @Nullable final LocalDate effectiveFromDate, final InternalCallContext internalCallContext) throws SubscriptionBaseApiException {

        final Catalog catalog;
        try {
            catalog = catalogInternalApi.getFullCatalog(true, true, internalCallContext);
            final DefaultSubscriptionBase subscription = (DefaultSubscriptionBase) getSubscriptionFromId(subscriptionId, internalCallContext);
            final DateTime effectiveDate = getEffectiveDateForNewBCD(bcd, effectiveFromDate, internalCallContext);
            final BCDEvent bcdEvent = BCDEventData.createBCDEvent(subscription, effectiveDate, bcd);
            dao.createBCDChangeEvent(subscription, bcdEvent, catalog, internalCallContext);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public int getDefaultBillCycleDayLocal(final Map<UUID, Integer> bcdCache, final SubscriptionBase subscription, final SubscriptionBase baseSubscription, final PlanPhaseSpecifier planPhaseSpecifier, final int accountBillCycleDayLocal, final Catalog catalog, final InternalTenantContext context) throws SubscriptionBaseApiException {
        try {
            final BillingAlignment alignment = catalog.billingAlignment(planPhaseSpecifier, subscription.getStartDate());
            return BillCycleDayCalculator.calculateBcdForAlignment(bcdCache, subscription, baseSubscription, alignment, context, accountBillCycleDayLocal);
        } catch (final CatalogApiException e) {
            throw new SubscriptionBaseApiException(e);
        }
    }

    @Override
    public UUID getAccountIdFromBundleId(final UUID bundleId, final InternalTenantContext context) throws SubscriptionBaseApiException {
        final CacheLoaderArgument arg = createAccountIdFromBundleIdCacheLoaderArgument(context);
        return accountIdCacheController.get(bundleId, arg);
    }

    @Override
    public UUID getBundleIdFromSubscriptionId(final UUID subscriptionId, final InternalTenantContext context) throws SubscriptionBaseApiException {
        final CacheLoaderArgument arg = createBundleIdFromSubscriptionIdCacheLoaderArgument(context);
        return bundleIdCacheController.get(subscriptionId, arg);
    }

    @Override
    public UUID getAccountIdFromSubscriptionId(final UUID subscriptionId, final InternalTenantContext context) throws SubscriptionBaseApiException {
        final UUID bundleId = getBundleIdFromSubscriptionId(subscriptionId, context);
        if (bundleId == null) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_GET_NO_BUNDLE_FOR_SUBSCRIPTION, subscriptionId);
        }
        final UUID accountId = getAccountIdFromBundleId(bundleId, context);
        if (accountId == null) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_ID, bundleId);
        }
        return accountId;
    }

    private CacheLoaderArgument createAccountIdFromBundleIdCacheLoaderArgument(final InternalTenantContext internalTenantContext) {
        final AccountIdFromBundleIdCacheLoader.LoaderCallback loaderCallback = new AccountIdFromBundleIdCacheLoader.LoaderCallback() {
            public UUID loadAccountId(final UUID bundleId, final InternalTenantContext internalTenantContext) {
                final SubscriptionBaseBundle bundle;
                try {
                    bundle = getBundleFromId(bundleId, internalTenantContext);
                } catch (final SubscriptionBaseApiException e) {
                    log.warn("Unable to retrieve bundle for id='{}'", bundleId);
                    return null;
                }
                return bundle.getAccountId();
            }
        };

        final Object[] args = {loaderCallback};
        return new CacheLoaderArgument(null, args, internalTenantContext);
    }

    private CacheLoaderArgument createBundleIdFromSubscriptionIdCacheLoaderArgument(final InternalTenantContext internalTenantContext) {
        final BundleIdFromSubscriptionIdCacheLoader.LoaderCallback loaderCallback = new BundleIdFromSubscriptionIdCacheLoader.LoaderCallback() {
            public UUID loadBundleId(final UUID subscriptionId, final InternalTenantContext internalTenantContext) {
                return dao.getBundleIdFromSubscriptionId(subscriptionId, internalTenantContext);
            }
        };

        final Object[] args = {loaderCallback};
        return new CacheLoaderArgument(null, args, internalTenantContext);
    }

    @VisibleForTesting
    DateTime getEffectiveDateForNewBCD(final int bcd, @Nullable final LocalDate effectiveFromDate, final InternalCallContext internalCallContext) {
        if (internalCallContext.getAccountRecordId() == null) {
            throw new IllegalStateException("Need to have a valid context with accountRecordId");
        }

        // Today as seen by this account
        final LocalDate startDate = effectiveFromDate != null ? effectiveFromDate : internalCallContext.toLocalDate(internalCallContext.getCreatedDate());

        // We want to compute a LocalDate in account TZ which maps to the provided 'bcd' and then compute an effectiveDate for when that BCD_CHANGE event needs to be triggered
        //
        // There is a bit of complexity to make sure the date we chose exists (e.g: a BCD of 31 in a february month would not make sense).
        final int currentDay = startDate.getDayOfMonth();
        final int lastDayOfMonth = startDate.dayOfMonth().getMaximumValue();

        final LocalDate requestedDate;
        if (bcd < currentDay) {
            final LocalDate startDatePlusOneMonth = startDate.plusMonths(1);
            final int lastDayOfNextMonth = startDatePlusOneMonth.dayOfMonth().getMaximumValue();
            final int originalBCDORLastDayOfMonth = bcd <= lastDayOfNextMonth ? bcd : lastDayOfNextMonth;
            requestedDate = new LocalDate(startDatePlusOneMonth.getYear(), startDatePlusOneMonth.getMonthOfYear(), originalBCDORLastDayOfMonth);
        } else if (bcd == currentDay && effectiveFromDate == null) {
            // will default to immediate event
            requestedDate = null;
        } else if (bcd <= lastDayOfMonth) {
            requestedDate = new LocalDate(startDate.getYear(), startDate.getMonthOfYear(), bcd);
        } else /* bcd > lastDayOfMonth && bcd > currentDay */ {
            requestedDate = new LocalDate(startDate.getYear(), startDate.getMonthOfYear(), lastDayOfMonth);
        }
        return requestedDate == null ? internalCallContext.getCreatedDate() : internalCallContext.toUTCDateTime(requestedDate);
    }

    private DateTime getBundleStartDateWithSanity(final UUID bundleId, @Nullable final SubscriptionBase baseSubscription, final Plan plan,
                                                  final DateTime effectiveDate, final Catalog catalog, final InternalTenantContext context) throws SubscriptionBaseApiException, CatalogApiException {
        switch (plan.getProduct().getCategory()) {
            case BASE:
                if (baseSubscription != null &&
                    (baseSubscription.getState() == EntitlementState.ACTIVE || baseSubscription.getState() == EntitlementState.PENDING)) {
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_BP_EXISTS, bundleId);
                }
                return effectiveDate;

            case ADD_ON:
                if (baseSubscription == null) {
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_NO_BP, bundleId);
                }
                if (effectiveDate.isBefore(baseSubscription.getStartDate())) {
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_INVALID_REQUESTED_DATE, effectiveDate.toString(), baseSubscription.getStartDate().toString());
                }
                addonUtils.checkAddonCreationRights(baseSubscription, plan, effectiveDate, catalog, context);
                return baseSubscription.getStartDate();

            case STANDALONE:
                if (baseSubscription != null) {
                    throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_BP_EXISTS, bundleId);
                }
                // Not really but we don't care, there is no alignment for STANDALONE subscriptions
                return effectiveDate;

            default:
                throw new SubscriptionBaseError(String.format("Can't create subscription of type %s",
                                                              plan.getProduct().getCategory().toString()));
        }
    }

    private List<EffectiveSubscriptionInternalEvent> convertEffectiveSubscriptionInternalEventFromSubscriptionTransitions(final SubscriptionBase subscription,
                                                                                                                          final InternalTenantContext context, final Collection<SubscriptionBaseTransition> transitions) {
        return ImmutableList.<EffectiveSubscriptionInternalEvent>copyOf(Collections2.transform(transitions, new Function<SubscriptionBaseTransition, EffectiveSubscriptionInternalEvent>() {
            @Override
            @Nullable
            public EffectiveSubscriptionInternalEvent apply(@Nullable final SubscriptionBaseTransition input) {
                return new DefaultEffectiveSubscriptionEvent((SubscriptionBaseTransitionData) input, ((DefaultSubscriptionBase) subscription).getAlignStartDate(), null, context.getAccountRecordId(), context.getTenantRecordId());
            }
        }));
    }

    // For forward-compatibility
    private DefaultSubscriptionBase getDefaultSubscriptionBase(final Entity subscriptionBase, final Catalog catalog, final InternalTenantContext context) throws CatalogApiException {
        if (subscriptionBase instanceof DefaultSubscriptionBase) {
            return (DefaultSubscriptionBase) subscriptionBase;
        } else {
            // Safe cast, see above
            return (DefaultSubscriptionBase) dao.getSubscriptionFromId(subscriptionBase.getId(), catalog, context);
        }
    }
}
