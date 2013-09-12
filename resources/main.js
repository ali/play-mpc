
$(function() 
{	
	$("#songpos-slider").slider(
	{
		range : "min",
		min : 0,
		max : $("#songpos-length").data("length"),
		value : $("#songpos-elapsed").data("elapsed"),
		step : 1,
		slide : function(event, ui) 
		{
			$("#songpos-elapsed").html(format_time(ui.value));		
		}
	});

	$("#volume-slider").slider(
	{
		range : "min",
		value : $("#volume").text(),
		min : 0,
		step : 5,
		max : 100,
		slide : function(event, ui) 
		{
			$("#volume").html(ui.value);
			updateVolumeIcon(ui.value);
		}
	});
		
	$('#playlist').on('click', '.remove', function(event)
	{
		// don't fire the onClick() event for the parent
		event.stopPropagation();
	});

	updateVolumeIcon($("#volume").text());
});


// this function also exists as scala variant in playlist.scala.html
function format_time(secs)
{
	var min = Math.floor(secs / 60);
	var sec = secs % 60;
	
	if (sec < 10)
		sec = "0" + sec;
	
	return min + ":" + sec;
}


var updateVolumeIcon = function(volume)
{
	$("#volume-icon").removeClass("glyphicon-volume-off");
	$("#volume-icon").removeClass("glyphicon-volume-up");
	$("#volume-icon").removeClass("glyphicon-volume-down");
	
	if (volume == 0)
		$("#volume-icon").addClass("glyphicon-volume-off"); else
	if (volume < 50)
		$("#volume-icon").addClass("glyphicon-volume-down"); else
		$("#volume-icon").addClass("glyphicon-volume-up");			
}
