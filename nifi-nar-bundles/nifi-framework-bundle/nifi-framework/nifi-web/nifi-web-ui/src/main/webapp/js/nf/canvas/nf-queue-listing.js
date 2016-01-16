/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global nf */

/**
 * Lists FlowFiles from a given connection.
 */
nf.QueueListing = (function () {

    var DEFAULT_SORT_COL = 'QUEUE_POSITION';
    var DEFAULT_SORT_ASC = true;

    /**
     * Initializes the listing request status dialog.
     */
    var initializeListingRequestStatusDialog = function () {
        // initialize the listing request progress bar
        var listingRequestProgressBar = $('#listing-request-percent-complete').progressbar();

        // configure the drop request status dialog
        $('#listing-request-status-dialog').modal({
            overlayBackground: false,
            handler: {
                close: function () {
                    // reset the progress bar
                    listingRequestProgressBar.find('div.progress-label').remove();

                    // update the progress bar
                    var label = $('<div class="progress-label"></div>').text('0%');
                    listingRequestProgressBar.progressbar('value', 0).append(label);

                    // clear the current button model
                    $('#listing-request-status-dialog').modal('setButtonModel', []);
                }
            }
        }).draggable({
            containment: 'parent',
            handle: '.dialog-header'
        });
    };

    /**
     * Downloads the content for the flowfile currently being viewed.
     */
    var downloadContent = function () {
        var dataUri = $('#flowfile-uri').text() + '/content';

        // conditionally include the cluster node id
        var clusterNodeId = $('#flowfile-cluster-node-id').text();
        if (!nf.Common.isBlank(clusterNodeId)) {
            window.open(dataUri + '?' + $.param({
                    'clusterNodeId': clusterNodeId
                }));
        } else {
            window.open(dataUri);
        }
    };

    /**
     * Views the content for the flowfile currently being viewed.
     */
    var viewContent = function () {
        var dataUri = $('#flowfile-uri').text() + '/content';

        // conditionally include the cluster node id
        var clusterNodeId = $('#flowfile-cluster-node-id').text();;
        if (!nf.Common.isBlank(clusterNodeId)) {
            var parameters = {
                'clusterNodeId': clusterNodeId
            };

            dataUri = dataUri + '?' + $.param(parameters);
        }

        // open the content viewer
        var contentViewerUrl = $('#nifi-content-viewer-url').text();

        // if there's already a query string don't add another ?... this assumes valid
        // input meaning that if the url has already included a ? it also contains at
        // least one query parameter
        if (contentViewerUrl.indexOf('?') === -1) {
            contentViewerUrl += '?';
        } else {
            contentViewerUrl += '&';
        }

        // open the content viewer
        window.open(contentViewerUrl + $.param({
                'ref': dataUri
            }));
    };

    /**
     * Initializes the flowfile details dialog.
     */
    var initFlowFileDetailsDialog = function () {
        $('#content-download').on('click', downloadContent);

        // only show if content viewer is configured
        if (nf.Common.isContentViewConfigured()) {
            $('#content-view').show();
            $('#content-view').on('click', viewContent);
        }

        $('#flowfile-details-tabs').tabbs({
            tabStyle: 'tab',
            selectedTabStyle: 'selected-tab',
            tabs: [{
                name: 'Details',
                tabContentId: 'flowfile-details-tab-content'
            }, {
                name: 'Attributes',
                tabContentId: 'flowfile-attributes-tab-content'
            }]
        });

        $('#flowfile-details-dialog').modal({
            headerText: 'FlowFile',
            overlayBackground: false,
            buttons: [{
                buttonText: 'Ok',
                handler: {
                    click: function () {
                        $('#flowfile-details-dialog').modal('hide');
                    }
                }
            }],
            handler: {
                close: function () {
                    // clear the details
                    $('#flowfile-attributes-container').empty();
                    $('#flowfile-cluster-node-id').text('');
                    $('#additional-flowfile-details').empty();
                }
            }
        }).draggable({
            containment: 'parent',
            handle: '.dialog-header'
        });
    };

    /**
     * Performs a listing on the specified connection.
     *
     * @param connection the connection
     * @param sortCol the sort column
     * @param sortAsc if sort is asc
     */
    var performListing = function (connection, sortCol, sortAsc) {
        var MAX_DELAY = 4;
        var cancelled = false;
        var listingRequest = null;
        var listingRequestTimer = null;

        return $.Deferred(function (deferred) {
            // updates the progress bar
            var updateProgress = function (percentComplete) {
                // remove existing labels
                var progressBar = $('#listing-request-percent-complete');
                progressBar.find('div.progress-label').remove();

                // update the progress bar
                var label = $('<div class="progress-label"></div>').text(percentComplete + '%');
                if (percentComplete > 0) {
                    label.css('margin-top', '-19px');
                }
                progressBar.progressbar('value', percentComplete).append(label);
            };

            // update the button model of the drop request status dialog
            $('#listing-request-status-dialog').modal('setButtonModel', [{
                buttonText: 'Stop',
                handler: {
                    click: function () {
                        cancelled = true;

                        // we are waiting for the next poll attempt
                        if (listingRequestTimer !== null) {
                            // cancel it
                            clearTimeout(listingRequestTimer);

                            // cancel the listing request
                            completeListingRequest();
                        }
                    }
                }
            }]);

            // completes the listing request by removing it
            var completeListingRequest = function () {
                $('#listing-request-status-dialog').modal('hide');

                var reject = cancelled;

                // ensure the listing requests are present
                if (nf.Common.isDefinedAndNotNull(listingRequest)) {
                    $.ajax({
                        type: 'DELETE',
                        url: listingRequest.uri,
                        dataType: 'json'
                    });

                    // use the listing request from when the listing completed
                    if (nf.Common.isEmpty(listingRequest.flowFileSummaries)) {
                        if (cancelled === false) {
                            reject = true;

                            // show the dialog
                            nf.Dialog.showOkDialog({
                                dialogContent: 'The queue has no FlowFiles.',
                                overlayBackground: false
                            });
                        }
                    } else {
                        // update the queue size
                        $('#total-flowfiles-count').text(nf.Common.formatInteger(listingRequest.queueSize.objectCount));
                        $('#total-flowfiles-size').text(nf.Common.formatDataSize(listingRequest.queueSize.byteCount));

                        // get the grid to load the data
                        var queueListingGrid = $('#queue-listing-table').data('gridInstance');
                        var queueListingData = queueListingGrid.getData();

                        // load the flowfiles
                        queueListingData.beginUpdate();
                        queueListingData.setItems(listingRequest.flowFileSummaries, 'uuid');
                        queueListingData.endUpdate();
                    }
                } else {
                    reject = true;
                }

                if (reject) {
                    deferred.reject();
                } else {
                    deferred.resolve();
                }
            };

            // process the listing request
            var processListingRequest = function (delay) {
                // update the percent complete
                updateProgress(listingRequest.percentCompleted);

                // update the status of the listing request
                $('#listing-request-status-message').text(listingRequest.state);

                // close the dialog if the
                if (listingRequest.finished === true || cancelled === true) {
                    completeListingRequest();
                } else {
                    // wait delay to poll again
                    listingRequestTimer = setTimeout(function () {
                        // clear the listing request timer
                        listingRequestTimer = null;

                        // schedule to poll the status again in nextDelay
                        pollListingRequest(Math.min(MAX_DELAY, delay * 2));
                    }, delay * 1000);
                }
            };

            // schedule for the next poll iteration
            var pollListingRequest = function (nextDelay) {
                $.ajax({
                    type: 'GET',
                    url: listingRequest.uri,
                    dataType: 'json'
                }).done(function(response) {
                    listingRequest = response.listingRequest;
                    processListingRequest(nextDelay);
                }).fail(completeListingRequest).fail(nf.Common.handleAjaxError);
            };

            // issue the request to list the flow files
            $.ajax({
                type: 'POST',
                url: connection.component.uri + '/listing-requests',
                data: {
                    sortColumn: sortCol,
                    sortOrder: sortAsc ? 'asc' : 'desc'
                },
                dataType: 'json'
            }).done(function(response) {
                // initialize the progress bar value
                updateProgress(0);

                // show the progress dialog
                $('#listing-request-status-dialog').modal('show');

                // process the drop request
                listingRequest = response.listingRequest;
                processListingRequest(1);
            }).fail(completeListingRequest).fail(nf.Common.handleAjaxError);
        }).promise();
    };

    /**
     * Shows the details for the specified flowfile.
     *
     * @param flowFileSummary the flowfile summary
     */
    var showFlowFileDetails = function (flowFileSummary) {
        // formats an flowfile detail
        var formatFlowFileDetail = function (label, value) {
            $('<div class="flowfile-detail"></div>').append(
                $('<div class="detail-name"></div>').text(label)).append(
                $('<div class="detail-value">' + nf.Common.formatValue(value) + '</div>').ellipsis()).append(
                $('<div class="clear"></div>')).appendTo('#additional-flowfile-details');
        };

        // formats the content value
        var formatContentValue = function (element, value) {
            if (nf.Common.isDefinedAndNotNull(value)) {
                element.removeClass('unset').text(value);
            } else {
                element.addClass('unset').text('No value set');
            }
        };

        $.ajax({
            type: 'GET',
            url: flowFileSummary.uri,
            dataType: 'json'
        }).done(function(response) {
            var flowFile = response.flowFile;

            // show the URI to this flowfile
            $('#flowfile-uri').text(flowFile.uri);

            // show the flowfile details dialog
            $('#flowfile-uuid').html(nf.Common.formatValue(flowFile.uuid));
            $('#flowfile-filename').html(nf.Common.formatValue(flowFile.filename));
            $('#flowfile-queue-position').html(nf.Common.formatValue(flowFile.position));
            $('#flowfile-file-size').html(nf.Common.formatValue(flowFile.contentClaimFileSize));
            $('#flowfile-queued-duration').text(nf.Common.formatDuration(flowFile.queuedDuration));
            $('#flowfile-lineage-duration').text(nf.Common.formatDuration(flowFile.lineageDuration));
            $('#flowfile-penalized').text(flowFile.penalized === true ? 'Yes' : 'No');

            // conditionally show the cluster node identifier
            if (nf.Common.isDefinedAndNotNull(flowFile.clusterNodeId)) {
                // save the cluster node id
                $('#flowfile-cluster-node-id').text(flowFile.clusterNodeId);

                // render the cluster node address
                formatFlowFileDetail('Node Address', flowFile.clusterNodeAddress);
            }

            if (nf.Common.isDefinedAndNotNull(flowFile.contentClaimContainer)) {
                // content claim
                formatContentValue($('#content-container'), flowFile.contentClaimContainer);
                formatContentValue($('#content-section'), flowFile.contentClaimSection);
                formatContentValue($('#content-identifier'), flowFile.contentClaimIdentifier);
                formatContentValue($('#content-offset'), flowFile.contentClaimOffset);
                formatContentValue($('#content-bytes'), flowFile.contentClaimFileSizeBytes);

                // input content file size
                var contentSize = $('#content-size');
                formatContentValue(contentSize, flowFile.contentClaimFileSize);
                if (nf.Common.isDefinedAndNotNull(flowFile.contentClaimFileSize)) {
                    // over the default tooltip with the actual byte count
                    contentSize.attr('title', nf.Common.formatInteger(flowFile.contentClaimFileSizeBytes) + ' bytes');
                }

                // show the content details
                $('#flowfile-content-details').show();
            } else {
                $('#flowfile-content-details').hide();
            }

            // attributes
            var attributesContainer = $('#flowfile-attributes-container');

            // get any action details
            $.each(flowFile.attributes, function (attributeName, attributeValue) {
                // create the attribute record
                var attributeRecord = $('<div class="attribute-detail"></div>')
                    .append($('<div class="attribute-name">' + nf.Common.formatValue(attributeName) + '</div>').ellipsis())
                    .appendTo(attributesContainer);

                // add the current value
                attributeRecord
                    .append($('<div class="attribute-value">' + nf.Common.formatValue(attributeValue) + '</div>').ellipsis())
                    .append('<div class="clear"></div>');
            });

            // show the dialog
            $('#flowfile-details-dialog').modal('show');
        }).fail(nf.Common.handleAjaxError);
    };

    /**
     * Resets the table size.
     */
    var resetTableSize = function () {
        var queueListingGrid = $('#queue-listing-table').data('gridInstance');
        if (nf.Common.isDefinedAndNotNull(queueListingGrid)) {
            queueListingGrid.resizeCanvas();
        }
    };

    return {
        init: function () {
            initializeListingRequestStatusDialog();
            initFlowFileDetailsDialog();

            // listen for browser resize events to update the page size
            $(window).resize(function () {
                resetTableSize();
            });

            // define a custom formatter for showing more processor details
            var moreDetailsFormatter = function (row, cell, value, columnDef, dataContext) {
                return '<img src="images/iconDetails.png" title="View Details" class="pointer show-flowfile-details" style="margin-top: 5px; float: left;"/>';
            };

            // function for formatting data sizes
            var dataSizeFormatter = function (row, cell, value, columnDef, dataContext) {
                return nf.Common.formatDataSize(value);
            };

            // function for formatting durations
            var durationFormatter = function (row, cell, value, columnDef, dataContext) {
                return nf.Common.formatDuration(value);
            }

            // function for formatting penalization
            var penalizedFormatter = function (row, cell, value, columnDef, dataContext) {
                var markup = '';

                if (value === true) {
                    markup += 'Yes';
                }

                return markup;
            }

            // initialize the queue listing table
            var queueListingColumns = [
                {id: 'moreDetails', field: 'moreDetails', name: '&nbsp;', sortable: false, resizable: false, formatter: moreDetailsFormatter, width: 50, maxWidth: 50},
                {id: 'QUEUE_POSITION', name: 'Position', field: 'position', sortable: true, resizable: false, width: 75, maxWidth: 75},
                {id: 'FLOWFILE_UUID', name: 'UUID', field: 'uuid', sortable: true, resizable: true},
                {id: 'FILENAME', name: 'Filename', field: 'filename', sortable: true, resizable: true},
                {id: 'FLOWFILE_SIZE', name: 'File Size', field: 'size', sortable: true, resizable: true, defaultSortAsc: false, formatter: dataSizeFormatter},
                {id: 'QUEUED_DURATION', name: 'Queued Duration', field: 'queuedDuration', sortable: true, resizable: true, formatter: durationFormatter},
                {id: 'FLOWFILE_AGE', name: 'Lineage Duration', field: 'lineageDuration', sortable: true, resizable: true, formatter: durationFormatter},
                {id: 'PENALIZATION', name: 'Penalized', field: 'penalized', sortable: true, resizable: false, width: 100, maxWidth: 100, formatter: penalizedFormatter}
            ];

            // conditionally show the cluster node identifier
            if (nf.Canvas.isClustered()) {
                queueListingColumns.push({id: 'clusterNodeAddress', name: 'Node', field: 'clusterNodeAddress', sortable: false, resizable: true});
            }

            var queueListingOptions = {
                forceFitColumns: true,
                enableTextSelectionOnCells: true,
                enableCellNavigation: false,
                enableColumnReorder: false,
                autoEdit: false
            };

            // initialize the dataview
            var queueListingData = new Slick.Data.DataView({
                inlineFilters: false
            });
            queueListingData.setItems([]);

            // initialize the grid
            var queueListingGrid = new Slick.Grid('#queue-listing-table', queueListingData, queueListingColumns, queueListingOptions);
            queueListingGrid.setSelectionModel(new Slick.RowSelectionModel());
            queueListingGrid.registerPlugin(new Slick.AutoTooltips());
            queueListingGrid.onSort.subscribe(function (e, args) {
                var connection = $('#queue-listing-table').data('connection');
                performListing(connection, args.sortCol.id, args.sortAsc);
            });

            // configure a click listener
            queueListingGrid.onClick.subscribe(function (e, args) {
                var target = $(e.target);

                // get the node at this row
                var item = queueListingData.getItem(args.row);

                // determine the desired action
                if (queueListingGrid.getColumns()[args.cell].id === 'moreDetails') {
                    if (target.hasClass('show-flowfile-details')) {
                        showFlowFileDetails(item);
                    }
                }
            });

            // wire up the dataview to the grid
            queueListingData.onRowCountChanged.subscribe(function (e, args) {
                queueListingGrid.updateRowCount();
                queueListingGrid.render();

                // update the total number of displayed flowfiles
                $('#displayed-flowfiles').text(args.current);
            });
            queueListingData.onRowsChanged.subscribe(function (e, args) {
                queueListingGrid.invalidateRows(args.rows);
                queueListingGrid.render();
            });

            // hold onto an instance of the grid
            $('#queue-listing-table').data('gridInstance', queueListingGrid);

            // initialize the number of display items
            $('#displayed-flowfiles').text('0');
        },

        /**
         * Shows the listing of the FlowFiles from a given connection.
         *
         * @param   {object}    The connection
         */
        listQueue: function (connection) {
            var queueListingGrid = $('#queue-listing-table').data('gridInstance');
            queueListingGrid.setSortColumn(DEFAULT_SORT_COL, DEFAULT_SORT_ASC);

            // perform the initial listing
            performListing(connection, DEFAULT_SORT_COL, DEFAULT_SORT_ASC).done(function () {
                // update the connection name
                var connectionName = nf.CanvasUtils.formatConnectionName(connection.component);
                if (connectionName === '') {
                    connectionName = 'Connection';
                }
                $('#queue-listing-header-text').text(connectionName);

                // show the listing container
                nf.Shell.showContent('#queue-listing-container').done(function () {
                    $('#queue-listing-table').removeData('connection');

                    // clear the table
                    var queueListingData = queueListingGrid.getData();

                    // clear the flowfiles
                    queueListingData.beginUpdate();
                    queueListingData.setItems([], 'uuid');
                    queueListingData.endUpdate();

                    // reset stats
                    $('#displayed-flowfiles, #total-flowfiles-count').text('0');
                    $('#total-flowfiles-size').text(nf.Common.formatDataSize(0));
                });

                // adjust the table size
                resetTableSize();

                // store the connection for access later
                $('#queue-listing-table').data('connection', connection);
            });
        }
    };
}());