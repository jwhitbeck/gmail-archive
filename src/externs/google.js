var auth = {};

/**
 * @constructor
 */
auth.OAuth2Client = function() {};
auth.OAuth2Client.prototype.setCredentials = function() {};
auth.OAuth2Client.prototype.generateAuthUrl = function(opts) {};
auth.OAuth2Client.prototype.getToken = function(opts, callback) {};

var google = {};
/**
* @returns {google.gmail.Gmail}
*/
google.gmail = function(opts) {};
google.gmail.Gmail;
google.gmail.Gmail.users = {};
google.gmail.Gmail.users.messages = {};
google.gmail.Gmail.users.messages.prototype.get = function(opts, callback) {};
google.gmail.Gmail.users.messages.prototype.list = function(opts, callback) {};
