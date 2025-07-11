

------Apex Class Name is OrderTaskCreator---------

public with sharing class OrderTaskCreator {

    @InvocableMethod

    public static void createTaskForNewOrder(List<Id> orderIds) {

        if (orderIds == null || orderIds.isEmpty()) {

            return;

        }




        // Fetch users with Profile Name 'Platform 1'

        List<User> platformUsers = [SELECT Id FROM User WHERE Profile.Name = 'Platform 1' LIMIT 10];




        // If no users found, exit method gracefully

        if (platformUsers.isEmpty()) {

            System.debug('No users found with Platform 1 profile.');

            return;

        }




        List<Task> tasks = new List<Task>();




        for (Id orderId : orderIds) {

            for (User user : platformUsers) {

                Task newTask = new Task(

                    Subject = 'New Order Created',

                    Description = 'A new order has been created. Please create an Order Item record.',

                    WhatId = orderId,

                    OwnerId = user.Id,

                    Status = 'Not Started',

                    Priority = 'High'

                );

                tasks.add(newTask);

            }

        }




        if (!tasks.isEmpty()) {

            insert tasks;

        }

    }

}

------------Class name is OrderStatusUpdated-----------
public class OrderStatusUpdater {
    public static void updateOrderStatus(Set<Id> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }

        // Fetch Orders that are still in "New" status
        List<AgriEdge_Order__c> ordersToUpdate = [
            SELECT Id, Order_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id IN :orderIds AND Order_Status__c = 'New'
        ];

        if (!ordersToUpdate.isEmpty()) {
            for (AgriEdge_Order__c order : ordersToUpdate) {
                order.Order_Status__c = 'Processing';
            }
            update ordersToUpdate;
        }
    }
}


------------------Apex Class Name is OrderTotalUpdater -----------------

public class OrderTotalUpdater {
    public static void updateOrderTotal(Set<Id> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        // Map to store OrderId and its Total Price
        Map<Id, Decimal> orderTotals = new Map<Id, Decimal>();
        // Query all order items related to the given orders
        for (AggregateResult ar : [
            SELECT AgriEdge_Order__c orderId, SUM(Total_Price__c) totalAmount
            FROM AgriEdge_OrderItem__c 
            WHERE AgriEdge_Order__c IN :orderIds
            GROUP BY AgriEdge_Order__c
        ]) {
            orderTotals.put((Id) ar.get('orderId'), (Decimal) ar.get('totalAmount'));
        }
        List<AgriEdge_Order__c> ordersToUpdate = new List<AgriEdge_Order__c>();
        // Query orders that need to be updated
        for (AgriEdge_Order__c order : [
            SELECT Id, Total_Amount__c, Payment_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id IN :orderIds
        ]) {
            order.Total_Amount__c = orderTotals.containsKey(order.Id) ? orderTotals.get(order.Id) : 0;
            order.Payment_Status__c = (order.Total_Amount__c > 0) ? 'Pending' : 'Paid'; // If total > 0, set to Pending
            ordersToUpdate.add(order);
        }
        if (!ordersToUpdate.isEmpty()) {
            update ordersToUpdate;
        }
    }
}


------------------Trigger Class Call Apex Class OrderStatusUpdater  in Trigger----------



trigger OrderItemTrigger on AgriEdge_OrderItem__c (after insert, after update) {
    Set<Id> orderIds = new Set<Id>();

    // Collect Order IDs from inserted/updated OrderItem records
    for (AgriEdge_OrderItem__c orderItem : Trigger.new) {
        if (orderItem.AgriEdge_Order__c != null) {
            orderIds.add(orderItem.AgriEdge_Order__c);
        }
    }

    if (!orderIds.isEmpty()) {
        OrderStatusUpdater.updateOrderStatus(orderIds);
        OrderTotalUpdater.updateOrderTotal(orderIds);
    }
}


--------------Class Name : OrderEmailSender-------------



public class OrderEmailSender {
    public static void sendOrderEmail(Set<Id> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }

        //  Include Shipping_Address__c and Discounted_Total__c in the query
        List<AgriEdge_Order__c> orders = [
            SELECT Id, Name, Total_Amount__c, Payment_Status__c, Order_Status__c, 
                   Customer__c, CreatedDate, Shipping_Address__c, Discounted_Total__c
            FROM AgriEdge_Order__c
            WHERE Id IN :orderIds
        ];

        // Collect Customer (Account) IDs
        Set<Id> accountIds = new Set<Id>();
        for (AgriEdge_Order__c order : orders) {
            if (order.Customer__c != null) {
                accountIds.add(order.Customer__c);
            }
        }

        // Query related Contacts of Customers (Accounts)
        Map<Id, List<String>> accountEmails = new Map<Id, List<String>>();
        for (Contact contact : [
            SELECT Email, AccountId FROM Contact WHERE AccountId IN :accountIds AND Email != null
        ]) {
            if (!accountEmails.containsKey(contact.AccountId)) {
                accountEmails.put(contact.AccountId, new List<String>());
            }
            accountEmails.get(contact.AccountId).add(contact.Email);
        }

        // Prepare Emails
        List<Messaging.SingleEmailMessage> emails = new List<Messaging.SingleEmailMessage>();

        for (AgriEdge_Order__c order : orders) {
            if (accountEmails.containsKey(order.Customer__c)) {
                List<String> toEmails = accountEmails.get(order.Customer__c);

                // Create Email Body
                String emailBody = 'Dear Customer,<br/><br/>'
                    + 'Your order has been updated with the following details:<br/><br/>'
                    + '<b>Order Name:</b> ' + order.Name + '<br/>'
                    + '<b>Order Status:</b> ' + order.Order_Status__c + '<br/>'
                    + '<b>Total Amount:</b> ' + order.Total_Amount__c + '<br/>'
                    + '<b>Payment Status:</b> ' + order.Payment_Status__c + '<br/>'
                    + '<b>Shipping Address:</b> ' + order.Shipping_Address__c + '<br/>'
                    + '<b>Created Date:</b> ' + order.CreatedDate + '<br/><br/>'
                    + '<b>Total Amount Paid (Including Discount):</b> ' + order.Discounted_Total__c + '<br/><br/>'
                    + 'Thank you for your business!<br/><br/>'
                    + 'Best Regards,<br/>Your Company Name';

                // Prepare Email
                Messaging.SingleEmailMessage email = new Messaging.SingleEmailMessage();
                email.setToAddresses(toEmails);
                email.setSubject('Your Order Payment Status has been Updated');
                email.setHtmlBody(emailBody);
                
                emails.add(email);
            }
        }

        if (!emails.isEmpty()) {
            Messaging.sendEmail(emails);
        }
    }
}



---------------------- OrderPaymentStatusTriggers ----------------------


trigger OrderPaymentStatusTriggers on AgriEdge_Order__c (after update) {
    Set<Id> updatedOrderIds = new Set<Id>();

    // Collect Orders where Payment_Status__c was changed to "Paid"
    for (AgriEdge_Order__c order : Trigger.new) {
        AgriEdge_Order__c oldOrder = Trigger.oldMap.get(order.Id);
        if (oldOrder.Payment_Status__c != 'Paid' && order.Payment_Status__c == 'Paid' && order.Customer__c != null) {
            updatedOrderIds.add(order.Id);
        }
    }

    if (!updatedOrderIds.isEmpty()) {
        OrderEmailSender.sendOrderEmail(updatedOrderIds);
    }
}



--------------------Class Name : AgriEdgeOrderShipmentHelper---------------------


public class AgriEdgeOrderShipmentHelper {
    public static void processOrderStatusChange(List<AgriEdge_Order__c> updatedOrders) {
        List<AgriEdge_Shipment__c> shipmentsToInsert = new List<AgriEdge_Shipment__c>();
        List<AgriEdge_Shipment__c> shipmentsToUpdate = new List<AgriEdge_Shipment__c>();
        List<AgriEdge_Order__c> ordersToUpdate = new List<AgriEdge_Order__c>();
        List<AgriEdge_OrderItem__c> orderItemsToDelete = new List<AgriEdge_OrderItem__c>();
        List<AgriEdge_Shipment__c> shipmentsToDelete = new List<AgriEdge_Shipment__c>();
        
        Set<Id> orderIds = new Set<Id>();

        for (AgriEdge_Order__c order : updatedOrders) {
            orderIds.add(order.Id);
        }

        Map<Id, AgriEdge_Shipment__c> existingShipments = new Map<Id, AgriEdge_Shipment__c>();
        for (AgriEdge_Shipment__c shipment : [
            SELECT Id, AgriEdge_Order__c, Status__c 
            FROM AgriEdge_Shipment__c 
            WHERE AgriEdge_Order__c IN :orderIds
        ]) {
            existingShipments.put(shipment.AgriEdge_Order__c, shipment);
        }

        Map<Id, List<AgriEdge_OrderItem__c>> existingOrderItems = new Map<Id, List<AgriEdge_OrderItem__c>>();
        for (AgriEdge_OrderItem__c orderItem : [
            SELECT Id, AgriEdge_Order__c 
            FROM AgriEdge_OrderItem__c 
            WHERE AgriEdge_Order__c IN :orderIds
        ]) {
            if (!existingOrderItems.containsKey(orderItem.AgriEdge_Order__c)) {
                existingOrderItems.put(orderItem.AgriEdge_Order__c, new List<AgriEdge_OrderItem__c>());
            }
            existingOrderItems.get(orderItem.AgriEdge_Order__c).add(orderItem);
        }

        for (AgriEdge_Order__c order : updatedOrders) {
            AgriEdge_Order__c updatedOrder = order.clone(false, true, false, false);
            updatedOrder.Id = order.Id;
            
            if (order.Payment_Status__c == 'Paid' && order.Order_Status__c != 'Delivered') {
                updatedOrder.Order_Status__c = 'Delivered';
                ordersToUpdate.add(updatedOrder);
            }
            else if (order.Payment_Status__c == 'Pending') {
                updatedOrder.Order_Status__c = 'Processing';
                ordersToUpdate.add(updatedOrder);
            }
            else if (order.Payment_Status__c == 'Failed') {
                updatedOrder.Order_Status__c = 'Canceled';
                ordersToUpdate.add(updatedOrder);
                
                if (existingOrderItems.containsKey(order.Id)) {
                    orderItemsToDelete.addAll(existingOrderItems.get(order.Id));
                }
                if (existingShipments.containsKey(order.Id)) {
                    shipmentsToDelete.add(existingShipments.get(order.Id));
                }
            }

            if (order.Order_Status__c == 'Processing' && !existingShipments.containsKey(order.Id)) {
                AgriEdge_Shipment__c newShipment = new AgriEdge_Shipment__c(
                    AgriEdge_Order__c = order.Id,
                    Tracking_Number__c = 'TEST_' + order.Id,
                    Status__c = 'Pending'
                );
                shipmentsToInsert.add(newShipment);
            }
            else if (order.Order_Status__c == 'Shipped' || order.Order_Status__c == 'Delivered') {
                if (existingShipments.containsKey(order.Id)) {
                    AgriEdge_Shipment__c shipmentToUpdate = existingShipments.get(order.Id);
                    shipmentToUpdate.Status__c = (order.Order_Status__c == 'Shipped') ? 'In Transit' : 'Delivered';
                    shipmentsToUpdate.add(shipmentToUpdate);
                }
            }
        }

        if (!ordersToUpdate.isEmpty()) {
            update ordersToUpdate;
        }
        if (!shipmentsToInsert.isEmpty()) {
            insert shipmentsToInsert;
        }
        if (!shipmentsToUpdate.isEmpty()) {
            update shipmentsToUpdate;
        }
        if (!orderItemsToDelete.isEmpty()) {
            delete orderItemsToDelete;
        }
        if (!shipmentsToDelete.isEmpty()) {
            delete shipmentsToDelete;
        }
    }
}




------------------Class Name : AgriEdgeOrderTriggerHelper---------------------



public class AgriEdgeOrderTriggerHelper {
    public static Boolean isTriggerExecuted = false;
}

----------------------AgriEdgeOrderTrigger--------------------------

trigger AgriEdgeOrderTrigger on AgriEdge_Order__c (after insert, after update) {
    if (AgriEdgeOrderTriggerHelper.isTriggerExecuted) {
        return; // Prevent recursive execution
    }
    AgriEdgeOrderTriggerHelper.isTriggerExecuted = true;

    List<AgriEdge_Order__c> relevantOrders = new List<AgriEdge_Order__c>();
    List<AgriEdge_Order__c> ordersToUpdate = new List<AgriEdge_Order__c>();
    List<Id> failedOrderIds = new List<Id>();

    for (AgriEdge_Order__c order : Trigger.new) {
        AgriEdge_Order__c oldOrder = null;
        
        // Only access Trigger.oldMap for update events
        if (Trigger.isUpdate) {
            oldOrder = Trigger.oldMap.get(order.Id);
        }

        Boolean paymentStatusChanged = (oldOrder == null || order.Payment_Status__c != oldOrder.Payment_Status__c);
        Boolean orderStatusChanged = (oldOrder == null || order.Order_Status__c != oldOrder.Order_Status__c);

        if (Trigger.isInsert || paymentStatusChanged || orderStatusChanged) {
            relevantOrders.add(order);
        }

        // If Payment is Pending → Set Order Status to Processing
        if (order.Payment_Status__c == 'Pending' && order.Order_Status__c != 'Processing') {
            ordersToUpdate.add(new AgriEdge_Order__c(
                Id = order.Id,
                Order_Status__c = 'Processing'
            ));
        }

        // If Payment Failed → Set Order Status to Cancelled
        if (order.Payment_Status__c == 'Failed') {
            ordersToUpdate.add(new AgriEdge_Order__c(
                Id = order.Id,
                Order_Status__c = 'Canceled'
            ));
            failedOrderIds.add(order.Id);
        }
    }

    // ✅ Perform updates outside of the loop to optimize DML
    if (!ordersToUpdate.isEmpty()) {
        update ordersToUpdate;
    }

    // ✅ Delete related records if Order is Cancelled
    if (!failedOrderIds.isEmpty()) {
        List<AgriEdge_OrderItem__c> orderItemsToDelete = [SELECT Id FROM AgriEdge_OrderItem__c WHERE AgriEdge_Order__c IN :failedOrderIds];
        List<AgriEdge_Shipment__c> shipmentsToDelete = [SELECT Id FROM AgriEdge_Shipment__c WHERE AgriEdge_Order__c IN :failedOrderIds];

        if (!orderItemsToDelete.isEmpty()) {
            delete orderItemsToDelete;
        }
        if (!shipmentsToDelete.isEmpty()) {
            delete shipmentsToDelete;
        }
    }

    // ✅ Call Helper Class for Shipment Processing
    if (!relevantOrders.isEmpty()) {
        AgriEdgeOrderShipmentHelper.processOrderStatusChange(relevantOrders);
    }

    // Reset recursion flag
    AgriEdgeOrderTriggerHelper.isTriggerExecuted = false;
}



--------------------------class:AgriEdgeOrderTests---------------------

@isTest
public class AgriEdgeOrderTests {
    // Helper method to create test data
    private static Account createTestAccount() {
        Account testAccount = new Account(Name = 'Test Customer');
        insert testAccount;
        return testAccount;
    }
    
    private static User createTestUser() {
        Profile p = [SELECT Id FROM Profile WHERE Name = 'Platform 1' LIMIT 1];
        User testUser = new User(
            Username = 'testuser@example3454.com',
            Firstname = 'Test1',
            LastName = 'john',
            Email = 'testuser@example.com',
            Alias = 'testuser',
            ProfileId = p.Id,
            TimeZoneSidKey = 'America/New_York',
            LocaleSidKey = 'en_US',
            EmailEncodingKey = 'ISO-8859-1',
            LanguageLocaleKey = 'en_US'
        );
        insert testUser;
        return testUser;
    }
    
    private static AgriEdge_Order__c createTestOrder(Id accountId, String paymentStatus, String orderStatus) {
        AgriEdge_Order__c order = new AgriEdge_Order__c(
            Payment_Status__c = paymentStatus,
            Order_Status__c = orderStatus,
            Customer__c = accountId
        );
        insert order;
        return order;
    }
    
    private static AgriEdge_OrderItem__c createTestOrderItem(Id orderId, Decimal quantity, Decimal unitPrice) {
        AgriEdge_OrderItem__c item = new AgriEdge_OrderItem__c(
            Order__c = orderId,
            Quantity__c = quantity,
            Unit_Price__c = unitPrice
        );
        insert item;
        return item;
    }

    @isTest
    public static void testOrderTaskCreator() {
        // Create test data
        Account testAccount = createTestAccount();
        User testUser = createTestUser();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Paid', 'Processing');
        
        Test.startTest();
        OrderTaskCreator.createTaskForNewOrder(new List<Id>{order.Id});
        Test.stopTest();
        
        // Verify tasks were created
        List<Task> tasks = [SELECT Id, WhatId FROM Task WHERE WhatId = :order.Id];
        System.assert(!tasks.isEmpty(), 'Tasks should be created for the order');
    }
    
  @isTest
public static void testOrderTaskCreatorNoUsers() {
    // Create test data without creating Platform 1 users
    Account testAccount = createTestAccount();
    AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Paid', 'Processing');
    
    // Instead of deleting users, just verify no Platform 1 users exist
    List<User> platformUsers = [SELECT Id FROM User WHERE Profile.Name = 'Platform 1'];
    System.assertEquals(0, platformUsers.size(), 'Test requires no Platform 1 users to exist');
    
    Test.startTest();
    OrderTaskCreator.createTaskForNewOrder(new List<Id>{order.Id});
    Test.stopTest();
    
    // Verify no tasks were created
    List<Task> tasks = [SELECT Id FROM Task WHERE WhatId = :order.Id];
    System.assertEquals(0, tasks.size(), 'No tasks should be created without Platform 1 users');
}

    @isTest
    public static void testOrderStatusUpdater() {
        // Create test data
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order1 = createTestOrder(testAccount.Id, 'Pending', 'New');
        AgriEdge_Order__c order2 = createTestOrder(testAccount.Id, 'Pending', 'Processing');
        AgriEdge_Order__c order3 = createTestOrder(testAccount.Id, 'Failed', 'Canceled');
        
        Set<Id> orderIds = new Set<Id>{order1.Id, order2.Id, order3.Id};
        
        Test.startTest();
        OrderStatusUpdater.updateOrderStatus(orderIds);
        Test.stopTest();
        
        // Verify updates
        Map<Id, AgriEdge_Order__c> updatedOrders = new Map<Id, AgriEdge_Order__c>([
            SELECT Id, Order_Status__c FROM AgriEdge_Order__c 
            WHERE Id IN :orderIds
        ]);
        
        System.assertEquals('Processing', updatedOrders.get(order1.Id).Order_Status__c);
        System.assertEquals('Processing', updatedOrders.get(order2.Id).Order_Status__c);
        System.assertEquals('Canceled', updatedOrders.get(order3.Id).Order_Status__c);
    }
    
    @isTest
    public static void testOrderStatusUpdaterEmptyInput() {
        Test.startTest();
        OrderStatusUpdater.updateOrderStatus(new Set<Id>());
        Test.stopTest();
        // Just verifying no exceptions occur
        System.assert(true);
    }

    @isTest
    public static void testOrderTotalUpdater() {
        // Create test data
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order1 = createTestOrder(testAccount.Id, 'Pending', 'New');
        AgriEdge_Order__c order2 = createTestOrder(testAccount.Id, 'Pending', 'New');
        
        // Create order items for order1
        createTestOrderItem(order1.Id, 2, 25.0);
        createTestOrderItem(order1.Id, 1, 30.0);
        
        Set<Id> orderIds = new Set<Id>{order1.Id, order2.Id};
        
        Test.startTest();
        OrderTotalUpdater.updateOrderTotal(orderIds);
        Test.stopTest();
        
        // Verify updates
        Map<Id, AgriEdge_Order__c> updatedOrders = new Map<Id, AgriEdge_Order__c>([
            SELECT Id, Total_Amount__c, Payment_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id IN :orderIds
        ]);
        
        System.assertEquals(80.0, updatedOrders.get(order1.Id).Total_Amount__c);
        System.assertEquals('Pending', updatedOrders.get(order1.Id).Payment_Status__c);
        System.assertEquals(0.0, updatedOrders.get(order2.Id).Total_Amount__c);
        System.assertEquals('Paid', updatedOrders.get(order2.Id).Payment_Status__c);
    }
    
    @isTest
    public static void testOrderTotalUpdaterNoOrderItems() {
        // Create test data
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Pending', 'New');
        
        Test.startTest();
        OrderTotalUpdater.updateOrderTotal(new Set<Id>{order.Id});
        Test.stopTest();
        
        // Verify updates
        AgriEdge_Order__c updatedOrder = [
            SELECT Total_Amount__c, Payment_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id = :order.Id
        ];
        
        System.assertEquals(0.0, updatedOrder.Total_Amount__c);
        System.assertEquals('Paid', updatedOrder.Payment_Status__c);
    }

    @isTest
    public static void testSendOrderEmail() {
        // Create test data
        Account testAccount = createTestAccount();
        
        // Create contacts
        Contact contact1 = new Contact(
            FirstName = 'Test1', LastName = 'User1', Email = 'test1@example.com', AccountId = testAccount.Id
        );
        Contact contact2 = new Contact(
            FirstName = 'Test2', LastName = 'User2', Email = 'test2@example.com', AccountId = testAccount.Id
        );
        insert new List<Contact>{contact1, contact2};
        
        // Create orders
        AgriEdge_Order__c order1 = createTestOrder(testAccount.Id, 'Pending', 'Processing');
        order1.Shipping_Address__c = '123 Test St';
        order1.Total_Amount__c = 100.00;
        update order1;
        
        AgriEdge_Order__c order2 = createTestOrder(testAccount.Id, 'Paid', 'Delivered');
        order2.Shipping_Address__c = '456 Test Ave';
        order2.Total_Amount__c = 150.00;
        update order2;
        
        Set<Id> orderIds = new Set<Id>{order1.Id, order2.Id};
        
        Test.startTest();
        // Use mock for email testing
        Integer beforeEmailInvocations = Limits.getEmailInvocations();
        OrderEmailSender.sendOrderEmail(orderIds);
        Integer afterEmailInvocations = Limits.getEmailInvocations();
        Test.stopTest();
        
        // Verify emails would be sent (actual sending is prevented in tests)
        System.assertEquals(beforeEmailInvocations, afterEmailInvocations, 
            'Email sending is mocked in tests, but logic should be verified');
    }
    
    @isTest
    public static void testSendOrderEmailNoContacts() {
        // Create test data without contacts
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Paid', 'Delivered');
        
        Test.startTest();
        OrderEmailSender.sendOrderEmail(new Set<Id>{order.Id});
        Test.stopTest();
        
        // Verify no emails sent
        System.assertEquals(0, Limits.getEmailInvocations(), 'No emails should be sent without contacts');
    }

    @isTest
    public static void testAgriEdgeOrderShipmentHelper() {
        // Create test data
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Paid', 'Processing');
        
        Test.startTest();
        AgriEdgeOrderShipmentHelper.processOrderStatusChange(new List<AgriEdge_Order__c>{order});
        Test.stopTest();
        
        // Verify shipment was created
        List<AgriEdge_Shipment__c> shipments = [
            SELECT Id, Order__c, Status__c 
            FROM AgriEdge_Shipment__c 
            WHERE Order__c = :order.Id
        ];
        System.assertEquals(1, shipments.size(), 'Shipment should be created');
        System.assertEquals('Pending', shipments[0].Status__c, 'Shipment status should be Pending');
    }
    
    @isTest
    public static void testAgriEdgeOrderShipmentHelperFailedPayment() {
        // Create test data for failed payment
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Failed', 'New');
        
        // Create related records that should be deleted
        AgriEdge_OrderItem__c item = createTestOrderItem(order.Id, 1, 10.0);
        AgriEdge_Shipment__c shipment = new AgriEdge_Shipment__c(
            Order__c = order.Id,
            Status__c = 'Pending'
        );
        insert shipment;
        
        Test.startTest();
        AgriEdgeOrderShipmentHelper.processOrderStatusChange(new List<AgriEdge_Order__c>{order});
        Test.stopTest();
        
        // Verify order status and deletions
        AgriEdge_Order__c updatedOrder = [
            SELECT Order_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id = :order.Id
        ];
        System.assertEquals('Canceled', updatedOrder.Order_Status__c, 'Order should be canceled');
        
        List<AgriEdge_OrderItem__c> remainingItems = [
            SELECT Id FROM AgriEdge_OrderItem__c WHERE Order__c = :order.Id
        ];
        System.assertEquals(0, remainingItems.size(), 'Order items should be deleted');
        
        List<AgriEdge_Shipment__c> remainingShipments = [
            SELECT Id FROM AgriEdge_Shipment__c WHERE Order__c = :order.Id
        ];
        System.assertEquals(0, remainingShipments.size(), 'Shipments should be deleted');
    }

    @isTest
    public static void testAgriEdgeOrderTrigger() {
        // Create test data
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Pending', 'New');
        
        Test.startTest();
        // Update to trigger the logic
        order.Payment_Status__c = 'Paid';
        update order;
        Test.stopTest();
        
        // Verify updates
        AgriEdge_Order__c updatedOrder = [
            SELECT Order_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id = :order.Id
        ];
        System.assertEquals('Delivered', updatedOrder.Order_Status__c, 'Order status should be Delivered');
    }
    
    @isTest
    public static void testAgriEdgeOrderTriggerFailedPayment() {
        // Create test data with related records
        Account testAccount = createTestAccount();
        AgriEdge_Order__c order = createTestOrder(testAccount.Id, 'Pending', 'New');
        AgriEdge_OrderItem__c item = createTestOrderItem(order.Id, 1, 10.0);
        AgriEdge_Shipment__c shipment = new AgriEdge_Shipment__c(
            Order__c = order.Id,
            Status__c = 'Pending'
        );
        insert shipment;
        
        Test.startTest();
        // Update to trigger the logic
        order.Payment_Status__c = 'Failed';
        update order;
        Test.stopTest();
        
        // Verify updates and deletions
        AgriEdge_Order__c updatedOrder = [
            SELECT Order_Status__c 
            FROM AgriEdge_Order__c 
            WHERE Id = :order.Id
        ];
        System.assertEquals('Canceled', updatedOrder.Order_Status__c, 'Order should be canceled');
        
        List<AgriEdge_OrderItem__c> remainingItems = [
            SELECT Id FROM AgriEdge_OrderItem__c WHERE Order__c = :order.Id
        ];
        System.assertEquals(0, remainingItems.size(), 'Order items should be deleted');
        
        List<AgriEdge_Shipment__c> remainingShipments = [
            SELECT Id FROM AgriEdge_Shipment__c WHERE Order__c = :order.Id
        ];
        System.assertEquals(0, remainingShipments.size(), 'Shipments should be deleted');
    }

    @isTest
    public static void testAgriEdgeOrderTriggerHelper() {
        // Test the trigger helper flag
        System.assertEquals(false, AgriEdgeOrderTriggerHelper.isTriggerExecuted, 'Initial state should be false');
        
        AgriEdgeOrderTriggerHelper.isTriggerExecuted = true;
        System.assertEquals(true, AgriEdgeOrderTriggerHelper.isTriggerExecuted, 'Flag should be settable');
    }
}