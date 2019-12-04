package com.kaplansoftware.imweb;

import com.kaplansoftware.imweb.config.StripeConfig;
import com.kaplansoftware.imweb.model.ProductDetails;
import com.kaplansoftware.imweb.service.ProductCache;
import com.kaplansoftware.imweb.service.UserRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Slf4j
@RequiredArgsConstructor
public class StripeWebhookController {

    private final boolean liveMode;
    private final String appIdKey;
    private final UserRepository userRepository;
    private final StripeConfig stripeConfig;
    private final ProductCache productCache;

    void handleEvent(Event event) {

        if (event.getLivemode() != liveMode)
            return;

        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        StripeObject stripeObject = dataObjectDeserializer.getObject().orElseGet(() -> {
            try {
                log.info("API version mismatch during event object deserialization");
                return dataObjectDeserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                log.error("Unable to deserialize Stripe Object: " + event.toString());
                //TODO Persist the unserializable event for administrative intervention
                return null;
            }
        });

        if (stripeObject == null)
            return;

        switch (event.getType()) {
            case "customer.created":
                customerCreate((Customer) stripeObject);
                break;
            case "customer.deleted":
                customerDelete((Customer) stripeObject);
                break;
            case "checkout.session.completed":
                Session session = (Session)stripeObject;
                if ("subscription".equals(session.getMode())) {
                    handleCheckoutSessionCompleted_subscription(session, event);
                }
                break;
            case "setup_intent.succeeded":
                setupIntentSucceeded((SetupIntent) stripeObject, event.getAccount());
                break;
            case "product.created":
            case "product.updated":
                updateProduct((Product) stripeObject, event.getAccount());
                break;
            case "product.deleted":
                evictProduct((Product) stripeObject, event.getAccount());
                break;
            case "customer.subscription.trial_will_end":
                subscriptionTrialEnding((Subscription) stripeObject);
                break;
            case "customer.subscription.deleted":
                subscriptionDeleted((Subscription) stripeObject, event.getAccount());
                break;
            case "invoice.upcoming":
                invoiceUpcoming((Invoice) stripeObject);
                break;
            case "invoice.payment_failed":
                invoiceFailed((Invoice) stripeObject, event.getAccount());
                break;
            default:
                log.debug("Unhandled Stripe event: {}", event.getType());
                break;
        }
    }

    private void evictProduct(Product product, String accountId) {
        productCache.evict(product.getId(),accountId);
    }

    private void updateProduct(Product product, String accountId) {
        ProductDetails details = ProductDetails.builder()
                .name(product.getName())
                .roles(product.getMetadata().getOrDefault("im_roles",""))
                .build();
        productCache.put(product.getId(),accountId,details);
    }

    private void invoiceFailed(Invoice stripeObject, String account) {
    }

    private void invoiceUpcoming(Invoice stripeObject) {
    }

    private void subscriptionDeleted(Subscription stripeObject, String account) {
    }

    private void subscriptionTrialEnding(Subscription stripeObject) {
    }

    private void setupIntentSucceeded(SetupIntent intent, String accountId) {
        log.info("Setup intent succeeded: {}", intent.getId());

        if (intent.getCustomer() == null || intent.getPaymentMethod() == null)
            return;

        // Setup intents can become recursive. Setting default subscription payment method creates setup intents
        // so we need to make sure we're only processing the ones that were initiated from the change card
        // page. That is identified by the metadata having "subId" in it.
        if (!intent.getMetadata().containsKey("subId"))
            return;

        RequestOptions requestOptions = getRequestOptions(accountId);

        try {
            PaymentMethod paymentMethod = PaymentMethod.retrieve(intent.getPaymentMethod(),requestOptions);
            CompletableFuture.runAsync(()->attachPaymentMethodToCustomer(intent.getCustomer(),paymentMethod,requestOptions))
                    .thenRunAsync(()->setDefaultSubscriptionPaymentMethod(intent.getMetadata().get("subId"),paymentMethod,requestOptions))
                    .thenRunAsync(()->setDefaultCustomerPaymentMethod(intent.getCustomer(),paymentMethod,requestOptions));
        } catch (Exception ignored) {}
        log.info("Updated customer and subscription payment method from intent ({})", intent.getId());
    }

    private void handleCheckoutSessionCompleted_subscription(Session session, Event event) {
        ObjectId userId = new ObjectId(session.getClientReferenceId());
        String accountId = event.getAccount();

        if (accountId != null) {
            RequestOptions reqOpts = getRequestOptions(accountId);
            String subId = session.getSubscription();
            try {
                byte[] saltBytes = Hex.decodeHex(stripeConfig.getSalt());
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                md.update(saltBytes);
                byte[] bytes = md.digest(subId.getBytes());
                Subscription subscription = Subscription.retrieve(subId,reqOpts);
                Map<String,Object> metadata = Collections.singletonMap("im_license",Hex.encodeHexString(bytes));
                Map<String,Object> params = Collections.singletonMap("metadata",metadata);
                subscription.update(params,reqOpts);
            } catch (NoSuchAlgorithmException | DecoderException | StripeException e) {
                log.error("Unable to add license to subscription. Event: {}",event.getId(),e);
            }
        }

        userRepository.findById(userId).ifPresent(user->{
            Document metadata = (Document)user.get("stripeMetadata");

            String acctId = metadata.getString("accountId");
            String customerId = metadata.getString("customerId");

            if (customerId == null) {
                metadata.put("customerId",session.getCustomer());
                metadata.put("accountId",event.getAccount());
                Map<String,Object> select = Collections.singletonMap("_id",userId);
                Map<String,Object> updates = Collections.singletonMap("stripeMetadata",metadata);
                userRepository.update(select,updates);
            }
        });
    }

    private void customerDelete(Customer customer) {
        userRepository.findByEmail(customer.getEmail()).ifPresent(user->{
            Document metadata = new Document();
            metadata.put("customerId",null);
            metadata.put("accountId",null);
            Map<String,Object> selectionCriteria = Collections.singletonMap("_id",user.getObjectId("_id"));
            Map<String,Object> updateFields = Collections.singletonMap("stripeMetadata",metadata);
            userRepository.update(selectionCriteria,updateFields);
        });
    }

    private void customerCreate(Customer customer) {
        userRepository.findByEmail(customer.getEmail()).ifPresent(user ->{
            Document metadata = (Document) user.get("stripeMetadata");
            String customerId = metadata.getString("customerId");
            ObjectId id = user.getObjectId("_id");

            // Make sure customerID is not already assigned to the user.
            if (isNotBlank(customerId))
                return;

            metadata.put("customerId",customer.getId());
            Map<String,Object> selectionCriteria = Collections.singletonMap("_id",id);
            Map<String,Object> fields = Collections.singletonMap("stripeMetadata",metadata);
            userRepository.update(selectionCriteria,fields);

            // Now update Stripe with the customer's localId
            customer.getMetadata().put(appIdKey,id.toString());
            Map<String,Object> updates = Collections.singletonMap("metadata",customer.getMetadata());
            try {
                customer.update(updates,getRequestOptions());
            } catch (StripeException e) {
                log.warn("Unable to assign local userid ({}) to Stripe metadata for customer ({}).",id,customer.getId());
            }
        });
    }

    private RequestOptions getRequestOptions() {
        return getRequestOptions(null);
    }

    private RequestOptions getRequestOptions(String accountId) {
        return RequestOptions.builder()
                .setApiKey(stripeConfig.getPrivateKey())
                .setStripeAccount(isBlank(accountId) ? null : accountId)
                .build();
    }

    private void attachPaymentMethodToCustomer(String customerId, PaymentMethod paymentMethod, RequestOptions reqOpt) {
        log.debug("Attaching payment method ({}) to customer ({}).", paymentMethod.getId(), customerId);
        try {
            paymentMethod.attach(Collections.singletonMap("customer",customerId),reqOpt);
        } catch (StripeException e) {
            e.printStackTrace();
        }
    }

    private void setDefaultSubscriptionPaymentMethod(String subscriptionId, PaymentMethod paymentMethod, RequestOptions reqOpt) {
        log.debug("Attaching payment method ({}) to subscription ({}).", paymentMethod.getId(), subscriptionId);
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId,reqOpt);
            subscription.update(Collections.singletonMap("default_payment_method",paymentMethod.getId()),reqOpt);
        } catch (StripeException e) {
            e.printStackTrace();
        }
    }

    private void setDefaultCustomerPaymentMethod(String customerId, PaymentMethod paymentMethod, RequestOptions reqOpt) {
        log.debug("Setting default payment method ({}) to customer ({}).", paymentMethod.getId(), customerId);
        try {
            Customer customer = Customer.retrieve(customerId, reqOpt);
            Map<String,Object> invoice_settings = Collections.singletonMap("default_payment_method",paymentMethod.getId());
            customer.update(Collections.singletonMap("invoice_settings",invoice_settings),reqOpt);
        } catch (StripeException e) {
            e.printStackTrace();
        }
    }
}
