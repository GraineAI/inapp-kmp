// The iOS SDK's Objective-C facade (GraineObjC, in GraineInApp's ObjC folder),
// as Kotlin/Native binds it: the part this library calls. Every selector is
// fixed in Swift with @objc(…); scripts/check-objc-header.mjs holds the two
// together. View controllers are declared as NSObject so the binding needs no
// UIKit; they are UIViewControllers.
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface GraineObjCRegistration : NSObject
- (void)unregister;
@end

@interface GraineObjCScheduledCall : NSObject
@property (nonatomic, readonly) NSString *identifier;
@property (nonatomic, readonly, nullable) NSDate *at;
@property (nonatomic, readonly) NSString *status;
@property (nonatomic, readonly, nullable) NSString *source;
@property (nonatomic, readonly, nullable) NSString *reason;
@end

@interface GraineObjCChat : NSObject
@property (nonatomic, readonly) NSString *snapshotJSON;
- (void)open;
- (void)openWithSize:(nullable NSString *)size;
- (void)newConversationWithCompletion:(void (^)(NSError * _Nullable error))completion;
- (void)close;
- (void)send:(NSString *)text;
- (void)retry;
- (void)clear;
- (void)answerWidgetWithId:(NSString *)widgetId dataJSON:(nullable NSString *)dataJSON summary:(NSString *)summary;
- (void)refreshUpcomingCalls;
- (void)cancelUpcomingCall:(NSString *)callId;
- (GraineObjCRegistration *)observe:(void (^)(void))observer;
@end

@interface GraineObjCCalls : NSObject
@property (nonatomic, readonly) NSString *snapshotJSON;
@property (nonatomic, copy) NSString *callTransport;
@property (nonatomic, readonly, nullable) NSString *transport;
@property (nonatomic, readonly, nullable) NSString *transportFallbackReason;
- (void)configureWithDisplayName:(NSString *)displayName ringtoneSound:(nullable NSString *)ringtoneSound ringTimeout:(double)ringTimeout includesCallsInRecents:(BOOL)includesCallsInRecents;
- (void)enableVoIPPushes;
- (nullable NSString *)startCallJSONWithReason:(nullable NSString *)reason variablesJSON:(nullable NSString *)variablesJSON;
- (void)prepareCallJSONWithReason:(nullable NSString *)reason variablesJSON:(nullable NSString *)variablesJSON;
- (void)answer;
- (void)decline;
- (void)end;
- (void)setMuted:(BOOL)muted;
- (void)setSpeaker:(BOOL)on;
- (nullable NSString *)callBack;
- (void)dismissMissedCall;
- (GraineObjCRegistration *)observe:(void (^)(void))observer;
- (GraineObjCRegistration *)observeOutcomes:(void (^)(NSString *json))observer;
- (GraineObjCRegistration *)observeIncoming:(void (^)(NSString *json))observer;
@property (nonatomic, readonly) NSString *callPermissionsJSON;
@property (nonatomic, readonly) NSString *callSetupProblemsJSON;
- (void)requestCallPermissionsJSONWithCompletion:(void (^)(NSString *json))completion;
- (BOOL)openCallSettings:(NSString *)item;
@end

@interface GraineObjCVoice : NSObject
@property (nonatomic, readonly) NSString *snapshotJSON;
- (void)attach;
- (void)start;
- (void)end;
- (void)setMuted:(BOOL)muted;
- (void)setCaptionsOn:(BOOL)on;
- (void)clearCaptions;
- (GraineObjCRegistration *)observe:(void (^)(void))observer;
@end

@interface GraineObjC : NSObject
+ (NSString *)errorDomain;
+ (GraineObjC *)configureWithBaseUrl:(NSString *)baseUrl publishableKey:(NSString *)publishableKey appVersion:(nullable NSString *)appVersion debug:(BOOL)debug endOnBackground:(BOOL)endOnBackground captureTaps:(BOOL)captureTaps transport:(nullable NSString *)transport variablesJSON:(nullable NSString *)variablesJSON;
+ (nullable GraineObjC *)configureWithBaseUrl:(NSString *)baseUrl publishableKey:(NSString *)publishableKey appVersion:(nullable NSString *)appVersion debug:(BOOL)debug endOnBackground:(BOOL)endOnBackground captureTaps:(BOOL)captureTaps transport:(nullable NSString *)transport variablesJSON:(nullable NSString *)variablesJSON name:(NSString *)name;
+ (nullable GraineObjC *)configureWithBaseUrl:(NSString *)baseUrl publishableKey:(NSString *)publishableKey appVersion:(nullable NSString *)appVersion debug:(BOOL)debug endOnBackground:(BOOL)endOnBackground captureTaps:(BOOL)captureTaps transport:(nullable NSString *)transport variablesJSON:(nullable NSString *)variablesJSON name:(NSString *)name chatSize:(nullable NSString *)chatSize;
+ (nullable GraineObjC *)current;
+ (nullable GraineObjC *)named:(NSString *)name;

@property (nonatomic, readonly) NSString *installId;
@property (nonatomic, readonly) BOOL memoryChoice;
@property (nonatomic, readonly, nullable) NSString *sessionJSON;
@property (nonatomic, readonly, nullable) NSString *themeJSON;
@property (nonatomic, readonly) NSString *currentVariablesJSON;
@property (nonatomic, readonly) GraineObjCChat *chat;
@property (nonatomic, readonly) GraineObjCCalls *calls;
@property (nonatomic, readonly) GraineObjCVoice *voice;
@property (nonatomic, readonly) BOOL bookingAvailable;

- (void)identifyJSON:(nullable NSString *)traitsJSON;
- (void)identifyJSON:(nullable NSString *)traitsJSON memory:(BOOL)memory;
- (void)setMemory:(BOOL)enabled;
- (void)forgetMemoryWithCompletion:(void (^)(NSInteger deleted, NSError * _Nullable error))completion;
- (void)memoriesJSONWithCompletion:(void (^)(NSString * _Nullable json, NSError * _Nullable error))completion;
- (void)setVariablesJSON:(nullable NSString *)json;
- (void)setScreenJSON:(nullable NSString *)json;
- (void)updateFieldJSON:(NSString *)json;
- (void)removeField:(NSString *)name;
- (void)clearScreen:(nullable NSString *)screenId;
- (void)trackEvent:(NSString *)name dataJSON:(nullable NSString *)dataJSON;
- (void)tap:(NSString *)name;
- (void)noteInteraction;
- (GraineObjCRegistration *)registerJSONAction:(NSString *)name handler:(void (^)(NSString *argsJSON, void (^answer)(NSString *status, NSString *message, NSString * _Nullable dataJSON)))handler;
- (void)scheduleCallJSONAt:(NSDate *)at reason:(nullable NSString *)reason completion:(void (^)(NSString * _Nullable json, NSError * _Nullable error))completion;
- (void)scheduledCallsJSONWithCompletion:(void (^)(NSString * _Nullable json, NSError * _Nullable error))completion;
- (void)cancelScheduledCall:(NSString *)callId completion:(void (^)(NSString * _Nullable status, NSError * _Nullable error))completion;
- (void)presentBookingWithReason:(nullable NSString *)reason completion:(void (^)(GraineObjCScheduledCall * _Nullable call))completion;
- (GraineObjCRegistration *)observeEvents:(void (^)(NSString *name, NSString *json))observer;
- (NSObject *)chatViewControllerWithOnClose:(void (^ _Nullable)(void))onClose;
- (NSObject *)callViewController;
- (NSObject *)voiceBarViewControllerWithName:(nullable NSString *)name onClose:(void (^ _Nullable)(void))onClose;
@end

NS_ASSUME_NONNULL_END
